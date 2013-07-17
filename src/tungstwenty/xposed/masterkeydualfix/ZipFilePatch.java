package tungstwenty.xposed.masterkeydualfix;

import static de.robv.android.xposed.XposedHelpers.getObjectField;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;


import libcore.io.BufferIterator;
import libcore.io.HeapBufferIterator;
import libcore.io.Streams;

public class ZipFilePatch {

	private static final int ENDHDR = 22;
	private static final int CENHDR = 46;

	private ZipFilePatch() {
		// No instantiation
	}

	public static void readCentralDir(ZipFile zipFile) throws IOException {
		/*
		 * Scan back, looking for the End Of Central Directory field. If
		 * the archive doesn't have a comment, we'll hit it on the first
		 * try.
		 * 
		 * No need to synchronize mRaf here -- we only do this when we
		 * first open the Zip file.
		 */

		RandomAccessFile mRaf = (RandomAccessFile) getObjectField(zipFile, "mRaf");
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
		Map<String, ZipEntry> mEntries = (Map<String, ZipEntry>) getObjectField(zipFile, "mEntries");
		for (int i = 0; i < numEntries; ++i) {
			ZipEntry newEntry = ZipEntryPatch.loadFromStream(hdrBuf, bin);
			String entryName = newEntry.getName();
			if (mEntries.put(entryName, newEntry) != null) {
				throw new ZipException("Duplicate entry name: " + entryName);
			}
		}
	}

	private static class RAFStream extends InputStream {

		RandomAccessFile mSharedRaf;
		long mOffset;
		long mLength;

		public RAFStream(RandomAccessFile raf, long pos) throws IOException {
			mSharedRaf = raf;
			mOffset = pos;
			mLength = raf.length();
		}

		@Override
		public int available() throws IOException {
			return (mOffset < mLength ? 1 : 0);
		}

		@Override
		public int read() throws IOException {
			return Streams.readSingleByte(this);
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			synchronized (mSharedRaf) {
				mSharedRaf.seek(mOffset);
				if (len > mLength - mOffset) {
					len = (int) (mLength - mOffset);
				}
				int count = mSharedRaf.read(b, off, len);
				if (count > 0) {
					mOffset += count;
					return count;
				} else {
					return -1;
				}
			}
		}

		@Override
		public long skip(long byteCount) throws IOException {
			if (byteCount > mLength - mOffset) {
				byteCount = mLength - mOffset;
			}
			mOffset += byteCount;
			return byteCount;
		}
	}

}
