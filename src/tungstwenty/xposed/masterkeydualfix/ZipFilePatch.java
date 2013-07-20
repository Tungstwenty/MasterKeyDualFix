package tungstwenty.xposed.masterkeydualfix;

import static de.robv.android.xposed.XposedHelpers.findField;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import libcore.io.BufferIterator;
import libcore.io.HeapBufferIterator;
import libcore.io.Streams;

public class ZipFilePatch {

	private static final int ENDHDR = 22;
	private static final int CENHDR = 46;

	private static Field fldEntries;
	private static Field fldRaf;

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
				RAFStream rafStream = new RAFStream(localRaf, ZipEntryPatch.fldLocalHeaderRelOffset.getLong(entry) + 6);
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
				rafStream.length = rafStream.offset + entry.getCompressedSize();
				if (ZipEntryPatch.fldCompressionMethod.getInt(entry) == ZipEntry.DEFLATED) {
					int bufSize = Math.max(1024, (int) Math.min(entry.getSize(), 65535L));
					return new ZipInflaterInputStream(rafStream, new Inflater(true), bufSize, entry);
				} else {
					return rafStream;
				}
			}
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
			RAFStream rafs = new RAFStream(mRaf, centralDirOffset);
			BufferedInputStream bin = new BufferedInputStream(rafs, 4096);
			byte[] hdrBuf = new byte[CENHDR]; // Reuse the same buffer for each entry.
			@SuppressWarnings("unchecked")
			Map<String, ZipEntry> mEntries = (Map<String, ZipEntry>) fldEntries.get(zipFile);
			for (int i = 0; i < numEntries; ++i) {
				ZipEntry newEntry = ZipEntryPatch.loadFromStream(hdrBuf, bin);
				String entryName = newEntry.getName();
				if (mEntries.put(entryName, newEntry) != null) {
					throw new ZipException("Duplicate entry name: " + entryName);
				}
			}
		} catch (IllegalAccessException e) {
			throw new IllegalAccessError(e.getMessage());
		}
	}

	private static class RAFStream extends InputStream {

		private RandomAccessFile sharedRaf;
		private long offset;
		private long length;

		public RAFStream(RandomAccessFile raf, long pos) throws IOException {
			sharedRaf = raf;
			offset = pos;
			length = raf.length();
		}

		@Override
		public int available() throws IOException {
			return (offset < length ? 1 : 0);
		}

		@Override
		public int read() throws IOException {
			return Streams.readSingleByte(this);
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			synchronized (sharedRaf) {
				sharedRaf.seek(offset);
				if (len > length - offset) {
					len = (int) (length - offset);
				}
				int count = sharedRaf.read(b, off, len);
				if (count > 0) {
					offset += count;
					return count;
				} else {
					return -1;
				}
			}
		}

		@Override
		public long skip(long byteCount) throws IOException {
			if (byteCount > length - offset) {
				byteCount = length - offset;
			}
			offset += byteCount;
			return byteCount;
		}
	}

	private static class ZipInflaterInputStream extends InflaterInputStream {
		private final ZipEntry entry;
		private long bytesRead = 0;

		public ZipInflaterInputStream(InputStream is, Inflater inf, int bsize, ZipEntry entry) {
			super(is, inf, bsize);
			this.entry = entry;
		}

		@Override
		public int read(byte[] buffer, int byteOffset, int byteCount) throws IOException {
			int i = super.read(buffer, byteOffset, byteCount);
			if (i != -1) {
				bytesRead += i;
			}
			return i;
		}

		@Override
		public int available() throws IOException {
			// if (closed) {
			// // Our superclass will throw an exception, but there's a jtreg test that
			// // explicitly checks that the InputStream returned from ZipFile.getInputStream
			// // returns 0 even when closed.
			// return 0;
			// }
			try {
				return super.available() == 0 ? 0 : (int) (entry.getSize() - bytesRead);
			} catch (IOException ex) {
				return 0;
			}
		}
	}
}
