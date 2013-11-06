package tungstwenty.xposed.masterkeydualfix;

import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findConstructorExact;
import static de.robv.android.xposed.XposedHelpers.findField;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import libcore.io.BufferIterator;
import libcore.io.HeapBufferIterator;

@SuppressWarnings("unchecked")
public class ZipFilePatch {

	private static final long LOCSIG = 0x04034b50;
	private static final long ENDSIG = 0x06054b50;


	private static final int ENDHDR = 22;
	private static final int CENHDR = 46;

	private static Field fldEntries;
	private static Field fldRaf;
	private static Field fldComment;

	private static Field fldOffset;
	private static Field fldLength;

	private static Constructor<InputStream> newRAFStream;
	private static Constructor<InflaterInputStream> newZipInflaterInputStream;

	static {
		try {
			fldEntries = findField(ZipFile.class, "mEntries");
		} catch (Throwable t) {
			fldEntries = findField(ZipFile.class, "entries");
		}
		try {
			fldRaf = findField(ZipFile.class, "mRaf");
		} catch (Throwable t) {
			fldRaf = findField(ZipFile.class, "raf");
		}
		try {
			fldComment = findField(ZipFile.class, "comment");
		} catch (Throwable t) {
			// This implementation didn't yet store the comment
			fldComment = null;
		}
		Class<?> clsRAFStream = findClass("java.util.zip.ZipFile.RAFStream", ZipFile.class.getClassLoader());
		newRAFStream = (Constructor<InputStream>) findConstructorExact(clsRAFStream, RandomAccessFile.class, long.class);
		try {
			fldOffset = findField(clsRAFStream, "offset");
		} catch (Throwable t) {
			fldOffset = findField(clsRAFStream, "mOffset");
		}
		try {
			fldLength = findField(clsRAFStream, "length");
		} catch (Throwable t) {
			fldLength = findField(clsRAFStream, "mLength");
		}
		Class<?> clsZipInflaterInputStream = findClass("java.util.zip.ZipFile.ZipInflaterInputStream",
		    ZipFile.class.getClassLoader());
		newZipInflaterInputStream = (Constructor<InflaterInputStream>) findConstructorExact(clsZipInflaterInputStream,
		    InputStream.class, Inflater.class, int.class, ZipEntry.class);
	}

	private ZipFilePatch() {
		// No instantiation
	}

	public static InputStream getInputStream(ZipFile zipFile, ZipEntry entry) throws IOException {
		try {
			// Make sure this ZipEntry is in this Zip file. We run it through the name lookup.
			entry = zipFile.getEntry(entry.getName());
			if (entry == null) {
				return null;
			}

			RandomAccessFile raf = (RandomAccessFile) fldRaf.get(zipFile);
			// Create an InputStream at the right part of the file.
			RandomAccessFile localRaf = raf;
			synchronized (localRaf) {
				// We don't know the entry data's start position. All we have is the
				// position of the entry's local header.
				// http://www.pkware.com/documents/casestudies/APPNOTE.TXT
				InputStream rafStream = newRAFStream.newInstance(localRaf,
				    ZipEntryPatch.fldLocalHeaderRelOffset.getLong(entry));
				DataInputStream is = new DataInputStream(rafStream);

				final int localMagic = Integer.reverseBytes(is.readInt());
				if (localMagic != LOCSIG) {
					throwZipException("Local File Header", localMagic);
				}

				is.skipBytes(2);

				// At position 6 we find the General Purpose Bit Flag.
				int gpbf = Short.reverseBytes(is.readShort()) & 0xffff;
				if ((gpbf & ZipEntryPatch.GPBF_UNSUPPORTED_MASK) != 0) {
					throw new ZipException("Invalid General Purpose Bit Flag: " + gpbf);
				}

				// Offset 26 has the file name length, and offset 28 has the extra field length.
				// These lengths can differ from the ones in the central header.
				is.skipBytes(18);
				int fileNameLength = Short.reverseBytes(is.readShort()) & 0xffff;
				int extraFieldLength = Short.reverseBytes(is.readShort()) & 0xffff;
				is.close();

				// Skip the variable-size file name and extra field data.
				rafStream.skip(fileNameLength + extraFieldLength);

				// The compressed or stored file data follows immediately after.
				if (ZipEntryPatch.fldCompressionMethod.getInt(entry) == ZipEntry.STORED) {
					fldLength.setLong(rafStream, fldOffset.getLong(rafStream) + entry.getSize());
					return rafStream;
				} else {
					fldLength.setLong(rafStream, fldOffset.getLong(rafStream) + entry.getCompressedSize());
					int bufSize = Math.max(1024, (int) Math.min(entry.getSize(), 65535L));
					return newZipInflaterInputStream.newInstance(rafStream, new Inflater(true), bufSize, entry);
				}
			}
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof IOException)
				throw (IOException) cause;
			if (cause instanceof RuntimeException)
				throw (RuntimeException) cause;
			throw new Error(cause);
		} catch (InstantiationException e) {
			throw new Error(e);
		} catch (IllegalAccessException e) {
			throw new IllegalAccessError(e.getMessage());
		}
	}

	public static void readCentralDir(ZipFile zipFile) throws IOException {
		try {
			// Scan back, looking for the End Of Central Directory field. If the zip file doesn't
			// have an overall comment (unrelated to any per-entry comments), we'll hit the EOCD
			// on the first try.
			// No need to synchronize raf here -- we only do this when we first open the zip file.
			RandomAccessFile raf = (RandomAccessFile) fldRaf.get(zipFile);
			long scanOffset = raf.length() - ENDHDR;
			if (scanOffset < 0) {
				throw new ZipException("File too short to be a zip file: " + raf.length());
			}

			raf.seek(0);
			final int headerMagic = Integer.reverseBytes(raf.readInt());
			if (headerMagic != LOCSIG) {
				throw new ZipException("Not a zip archive");
			}

			long stopOffset = scanOffset - 65536;
			if (stopOffset < 0) {
				stopOffset = 0;
			}

			while (true) {
				raf.seek(scanOffset);
				if (Integer.reverseBytes(raf.readInt()) == ENDSIG) {
					break;
				}

				scanOffset--;
				if (scanOffset < stopOffset) {
					throw new ZipException("End Of Central Directory signature not found");
				}
			}

			// Read the End Of Central Directory. ENDHDR includes the signature bytes,
			// which we've already read.
			byte[] eocd = new byte[ENDHDR - 4];
			raf.readFully(eocd);

			// Pull out the information we need.
			BufferIterator it = HeapBufferIterator.iterator(eocd, 0, eocd.length, ByteOrder.LITTLE_ENDIAN);
			int diskNumber = it.readShort() & 0xffff;
			int diskWithCentralDir = it.readShort() & 0xffff;
			int numEntries = it.readShort() & 0xffff;
			int totalNumEntries = it.readShort() & 0xffff;
			it.skip(4); // Ignore centralDirSize.
			long centralDirOffset = ((long) it.readInt()) & 0xffffffffL;
			int commentLength = it.readShort() & 0xffff;

			if (numEntries != totalNumEntries || diskNumber != 0 || diskWithCentralDir != 0) {
				throw new ZipException("Spanned archives not supported");
			}

			if (commentLength > 0) {
				byte[] commentBytes = new byte[commentLength];
				raf.readFully(commentBytes);
				if (fldComment != null)
					fldComment.set(zipFile, new String(commentBytes, 0, commentBytes.length, ZipEntryPatch.UTF_8));
			}

			// Seek to the first CDE and read all entries.
			// We have to do this now (from the constructor) rather than lazily because the
			// public API doesn't allow us to throw IOException except from the constructor
			// or from getInputStream.
			InputStream rafStream = newRAFStream.newInstance(raf, centralDirOffset);
			BufferedInputStream bufferedStream = new BufferedInputStream(rafStream, 4096);
			byte[] hdrBuf = new byte[CENHDR]; // Reuse the same buffer for each entry.
			Map<String, ZipEntry> mEntries = (Map<String, ZipEntry>) fldEntries.get(zipFile);
			for (int i = 0; i < numEntries; ++i) {
				ZipEntry newEntry = ZipEntryPatch.loadFromStream(hdrBuf, bufferedStream);
				if (ZipEntryPatch.fldLocalHeaderRelOffset.getLong(newEntry) >= centralDirOffset) {
					throw new ZipException("Local file header offset is after central directory");
				}
				String entryName = newEntry.getName();
				if (mEntries.put(entryName, newEntry) != null) {
					throw new ZipException("Duplicate entry name: " + entryName);
				}
			}
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof IOException)
				throw (IOException) cause;
			if (cause instanceof RuntimeException)
				throw (RuntimeException) cause;
			throw new Error(cause);
		} catch (InstantiationException e) {
			throw new Error(e);
		} catch (IllegalAccessException e) {
			throw new IllegalAccessError(e.getMessage());
		}
	}

	static void throwZipException(String msg, int magic) throws ZipException {
		final String hexString = IntegralToString.intToHexString(magic, true, 8);
		throw new ZipException(msg + " signature not found; was " + hexString);
	}
}
