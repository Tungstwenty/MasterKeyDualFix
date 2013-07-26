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

	private static final int ENDHDR = 22;
	private static final int CENHDR = 46;

	private static Field fldEntries;
	private static Field fldRaf;

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
				// position of the entry's local header. At position 6 we find the
				// General Purpose Bit Flag.
				// http://www.pkware.com/documents/casestudies/APPNOTE.TXT
				InputStream rafStream = newRAFStream.newInstance(localRaf,
				    ZipEntryPatch.fldLocalHeaderRelOffset.getLong(entry) + 6);

				DataInputStream is = new DataInputStream(rafStream);
				int gpbf = Short.reverseBytes(is.readShort()) & 0xffff;

				if ((gpbf & ZipEntryPatch.GPBF_UNSUPPORTED_MASK) != 0) {
					throw new ZipException("Invalid General Purpose Bit Flag: " + gpbf);
				}

				// At position 28 we find the length of the extra data. In some cases
				// this length differs from the one coming in the central header.
				is.skipBytes(20);
				int localExtraLenOrWhatever = Short.reverseBytes(is.readShort()) & 0xffff;
				is.close();

				// Skip the name and this "extra" data or whatever it is:
				rafStream.skip(ZipEntryPatch.fldNameLength.getInt(entry) + localExtraLenOrWhatever);
				fldLength.setLong(rafStream, fldOffset.getLong(rafStream) + entry.getCompressedSize());
				if (ZipEntryPatch.fldCompressionMethod.getInt(entry) == ZipEntry.DEFLATED) {
					int bufSize = Math.max(1024, (int) Math.min(entry.getSize(), 65535L));
					return newZipInflaterInputStream.newInstance(rafStream, new Inflater(true), bufSize, entry);
				} else {
					return rafStream;
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
			/*
			 * Scan back, looking for the End Of Central Directory field. If
			 * the archive doesn't have a comment, we'll hit it on the first
			 * try.
			 * 
			 * No need to synchronize mRaf here -- we only do this when we
			 * first open the Zip file.
			 */

			RandomAccessFile mRaf = (RandomAccessFile) fldRaf.get(zipFile);
			long scanOffset = mRaf.length() - ENDHDR;
			if (scanOffset < 0) {
				throw new ZipException("File too short to be a zip file: " + mRaf.length());
			}

			long stopOffset = scanOffset - 65536;
			if (stopOffset < 0) {
				stopOffset = 0;
			}

			final int ENDHEADERMAGIC = 0x06054b50;
			while (true) {
				mRaf.seek(scanOffset);
				if (Integer.reverseBytes(mRaf.readInt()) == ENDHEADERMAGIC) {
					break;
				}

				scanOffset--;
				if (scanOffset < stopOffset) {
					throw new ZipException("EOCD not found; not a Zip archive?");
				}
			}

			// Read the End Of Central Directory. We could use ENDHDR instead of the magic number 18,
			// but we don't actually need all the header.
			byte[] eocd = new byte[18];
			mRaf.readFully(eocd);

			// Pull out the information we need.
			BufferIterator it = HeapBufferIterator.iterator(eocd, 0, eocd.length, ByteOrder.LITTLE_ENDIAN);
			int diskNumber = it.readShort() & 0xffff;
			int diskWithCentralDir = it.readShort() & 0xffff;
			int numEntries = it.readShort() & 0xffff;
			int totalNumEntries = it.readShort() & 0xffff;
			it.skip(4); // Ignore centralDirSize.
			int centralDirOffset = it.readInt();

			if (numEntries != totalNumEntries || diskNumber != 0 || diskWithCentralDir != 0) {
				throw new ZipException("spanned archives not supported");
			}

			// Seek to the first CDE and read all entries.
			InputStream rafs = newRAFStream.newInstance(mRaf, centralDirOffset);
			BufferedInputStream bin = new BufferedInputStream(rafs, 4096);
			byte[] hdrBuf = new byte[CENHDR]; // Reuse the same buffer for each entry.
			Map<String, ZipEntry> mEntries = (Map<String, ZipEntry>) fldEntries.get(zipFile);
			for (int i = 0; i < numEntries; ++i) {
				ZipEntry newEntry = ZipEntryPatch.loadFromStream(hdrBuf, bin);
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
}
