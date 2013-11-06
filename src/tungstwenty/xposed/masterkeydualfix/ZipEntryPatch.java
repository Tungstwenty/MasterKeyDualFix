package tungstwenty.xposed.masterkeydualfix;

import static de.robv.android.xposed.XposedHelpers.findField;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;

import libcore.io.BufferIterator;
import libcore.io.HeapBufferIterator;
import libcore.io.Streams;

public class ZipEntryPatch {

	private static final long CENSIG = 0x2014b50;

	private static final int GPBF_ENCRYPTED_FLAG = 1 << 0;
	/* package */ static final int GPBF_UNSUPPORTED_MASK = GPBF_ENCRYPTED_FLAG;

	static final Charset UTF_8 = Charset.forName("UTF-8");


	/* package */ static Field fldCompressionMethod;
	private static Field fldTime;
	private static Field fldModDate;
	/* package */ static Field fldNameLength;
	/* package */ static Field fldLocalHeaderRelOffset;
	private static Field fldName;

	
	static {
		fldCompressionMethod = findField(ZipEntry.class, "compressionMethod");
		fldTime = findField(ZipEntry.class, "time");
		fldModDate = findField(ZipEntry.class, "modDate");
		fldNameLength = findField(ZipEntry.class, "nameLength");
		try {
			fldLocalHeaderRelOffset = findField(ZipEntry.class, "localHeaderRelOffset");
		} catch (Throwable t) {
			fldLocalHeaderRelOffset = findField(ZipEntry.class, "mLocalHeaderRelOffset");
		}
		fldName = findField(ZipEntry.class, "name");
	}

	
	
	// No constructor
	private ZipEntryPatch() {
	}

	static ZipEntry loadFromStream(byte[] cdeHdrBuf, BufferedInputStream cdStream) throws IOException {
		try {
			ZipEntry result = new ZipEntry("");

			Streams.readFully(cdStream, cdeHdrBuf, 0, cdeHdrBuf.length);

			BufferIterator it = HeapBufferIterator.iterator(cdeHdrBuf, 0, cdeHdrBuf.length,
				ByteOrder.LITTLE_ENDIAN);

			int sig = it.readInt();
			if (sig != CENSIG) {
				ZipFilePatch.throwZipException("Central Directory Entry", sig);
			}

			it.seek(8);
			int gpbf = it.readShort() & 0xffff;

			if ((gpbf & GPBF_UNSUPPORTED_MASK) != 0) {
				throw new ZipException("Invalid General Purpose Bit Flag: " + gpbf);
			}

			int compressionMethod;
			compressionMethod = it.readShort() & 0xffff;
			fldCompressionMethod.setInt(result, compressionMethod);
			int time;
			time = it.readShort() & 0xffff;
			fldTime.setInt(result, time);
			int modDate;
			modDate = it.readShort() & 0xffff;
			fldModDate.setInt(result, modDate);

			// These are 32-bit values in the file, but 64-bit fields in this object.
			long crc;
			crc = ((long) it.readInt()) & 0xffffffffL;
			result.setCrc(crc);
			long compressedSize;
			compressedSize = ((long) it.readInt()) & 0xffffffffL;
			result.setCompressedSize(compressedSize);
			long size;
			size = ((long) it.readInt()) & 0xffffffffL;
			result.setSize(size);

			int nameLength;
			nameLength = it.readShort() & 0xffff;
			fldNameLength.setInt(result, nameLength);
			int extraLength = it.readShort() & 0xffff;
			int commentByteCount = it.readShort() & 0xffff;

			// This is a 32-bit value in the file, but a 64-bit field in this object.
			it.seek(42);
			long localHeaderRelOffset;
			localHeaderRelOffset = ((long) it.readInt()) & 0xffffffffL;
			fldLocalHeaderRelOffset.setLong(result, localHeaderRelOffset);

			byte[] nameBytes = new byte[nameLength];
			Streams.readFully(cdStream, nameBytes, 0, nameBytes.length);
			if (containsNulByte(nameBytes)) {
				throw new ZipException("Filename contains NUL byte: " + Arrays.toString(nameBytes));
			}
			String name;
			name = new String(nameBytes, 0, nameBytes.length, UTF_8);
			fldName.set(result, name);

			if (extraLength > 0) {
				byte[] extra;
				extra = new byte[extraLength];
				Streams.readFully(cdStream, extra, 0, extraLength);
				result.setExtra(extra);
			}

			// The RI has always assumed UTF-8. (If GPBF_UTF8_FLAG isn't set, the encoding is
			// actually IBM-437.)
			if (commentByteCount > 0) {
				byte[] commentBytes = new byte[commentByteCount];
				Streams.readFully(cdStream, commentBytes, 0, commentByteCount);
				String comment;
				comment = new String(commentBytes, 0, commentBytes.length, UTF_8);
				result.setComment(comment);
			}

			return result;
		} catch (IllegalAccessException e) {
			throw new IllegalAccessError(e.getMessage());
		}
	}

	private static boolean containsNulByte(byte[] bytes) {
		for (byte b : bytes) {
			if (b == 0) {
				return true;
			}
		}
		return false;
	}

}
