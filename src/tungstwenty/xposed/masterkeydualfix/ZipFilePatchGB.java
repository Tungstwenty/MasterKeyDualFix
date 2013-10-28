package tungstwenty.xposed.masterkeydualfix;

import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findField;
import static de.robv.android.xposed.XposedHelpers.findConstructorExact;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

@SuppressWarnings("unchecked")
public class ZipFilePatchGB {

    private static final int ENDHDR = 22;

    private static Field field_mRaf;
    private static Field field_mEntries;
    private static Field field_ler;

    private static Constructor<InputStream> constructor_RAFStream;
    private static Constructor<ZipEntry> constructor_ZipEntry;

    private static Class<?> class_LittleEndianReader;

    static {
        field_mRaf = findField(ZipFile.class, "mRaf");
        field_mEntries = findField(ZipFile.class, "mEntries");
        field_ler = findField(ZipFile.class, "ler");
        Class<?> class_RAFStream = findClass("java.util.zip.ZipFile.RAFStream", ZipFile.class.getClassLoader());
        constructor_RAFStream = (Constructor<InputStream>) findConstructorExact(class_RAFStream, RandomAccessFile.class, long.class);
        class_LittleEndianReader = findClass("java.util.zip.ZipEntry.LittleEndianReader", ZipEntry.class.getClassLoader());
        constructor_ZipEntry = (Constructor<ZipEntry>) findConstructorExact(ZipEntry.class, class_LittleEndianReader, InputStream.class);
    }

    /**
     * Find the central directory and read the contents.
     *
     * <p>The central directory can be followed by a variable-length comment
     * field, so we have to scan through it backwards.  The comment is at
     * most 64K, plus we have 18 bytes for the end-of-central-dir stuff
     * itself, plus apparently sometimes people throw random junk on the end
     * just for the fun of it.
     *
     * <p>This is all a little wobbly.  If the wrong value ends up in the EOCD
     * area, we're hosed. This appears to be the way that everybody handles
     * it though, so we're in good company if this fails.
     */
    public static void readCentralDir(ZipFile zipFile) throws IOException {
        try {
            readCentralDirReflect(zipFile);
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
        } catch (NoSuchMethodException e) {
            throw new Error(e);
        }
    }

    /**
     * Reflect readCentralDir
     * @param zipFile
     * @throws IOException
     * @throws IllegalAccessException
     * @throws InstantiationException
     * @throws InvocationTargetException
     * @throws NoSuchMethodException
     */
    public static void readCentralDirReflect(ZipFile zipFile) throws IOException, IllegalAccessException, InstantiationException, InvocationTargetException, NoSuchMethodException {
        /*
         * Scan back, looking for the End Of Central Directory field.  If
         * the archive doesn't have a comment, we'll hit it on the first
         * try.
         *
         * No need to synchronize mRaf here -- we only do this when we
         * first open the Zip file.
         */
        RandomAccessFile mRaf = (RandomAccessFile) field_mRaf.get(zipFile);
        long scanOffset = mRaf.length() - ENDHDR;
        if (scanOffset < 0) {
            throw new ZipException("too short to be Zip");
        }

        long stopOffset = scanOffset - 65536;
        if (stopOffset < 0) {
            stopOffset = 0;
        }

        while (true) {
            mRaf.seek(scanOffset);
            // FIXME: if (ZipEntry.readIntLE(mRaf) == 101010256L) {
            if (readIntLE(mRaf) == 101010256L) {
                break;
            }

            scanOffset--;
            if (scanOffset < stopOffset) {
                throw new ZipException("EOCD not found; not a Zip archive?");
            }
        }

        /*
         * Found it, read the EOCD.
         *
         * For performance we want to use buffered I/O when reading the
         * file.  We wrap a buffered stream around the random-access file
         * object.  If we just read from the RandomAccessFile we'll be
         * doing a read() system call every time.
         */
        // FIXME: RAFStream rafs = new RAFStream(mRaf, mRaf.getFilePointer());
        InputStream rafs = constructor_RAFStream.newInstance(mRaf, mRaf.getFilePointer());
        BufferedInputStream bin = new BufferedInputStream(rafs, ENDHDR);

        // FIXME: int diskNumber = ler.readShortLE(bin);
        // FIXME: int diskWithCentralDir = ler.readShortLE(bin);
        // FIXME: int numEntries = ler.readShortLE(bin);
        // FIXME: int totalNumEntries = ler.readShortLE(bin);
        // FIXME: /*centralDirSize =*/ ler.readIntLE(bin);
        // FIXME: long centralDirOffset = ler.readIntLE(bin);
        // FIXME: /*commentLen =*/ ler.readShortLE(bin);
        Object ler = field_ler.get(zipFile);
        int diskNumber = readShortLE(ler, bin);
        int diskWithCentralDir = readShortLE(ler, bin);
        int numEntries = readShortLE(ler, bin);
        int totalNumEntries = readShortLE(ler, bin);
        /*centralDirSize =*/ readIntLE(ler, bin);
        long centralDirOffset = readIntLE(ler, bin);
        /*commentLen =*/ readShortLE(ler, bin);

        if (numEntries != totalNumEntries ||
            diskNumber != 0 ||
            diskWithCentralDir != 0) {
            throw new ZipException("spanned archives not supported");
        }

        /*
         * Seek to the first CDE and read all entries.
         */
        // FIXME: rafs = new RAFStream(mRaf, centralDirOffset);
        rafs = constructor_RAFStream.newInstance(mRaf, centralDirOffset);
        bin = new BufferedInputStream(rafs, 4096);
        LinkedHashMap<String, ZipEntry> mEntries = (LinkedHashMap<String, ZipEntry>) field_mEntries.get(zipFile);
        for (int i = 0; i < numEntries; i++) {
            // FIXME: ZipEntry newEntry = new ZipEntry(ler, bin);
            // FIXME: mEntries.put(newEntry.getName(), newEntry);
            ZipEntry newEntry = constructor_ZipEntry.newInstance(ler, bin);
            String entryName = newEntry.getName();
            if (mEntries.put(entryName, newEntry) != null) {
                throw new ZipException("Duplicate entry name: " + entryName);
            }
        }
    }

    static <T> T callReturnMethod(Class<?> clazz, Object receiver, String methodName, Class<T> returnType,
                                  Class<?>[] parameterTypes, Object[] args) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        Method method = clazz.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return (T) method.invoke(receiver, args);
    }

    /**
     * int readShortLE(InputStream in) throws IOException
     * @param ler instance of ZipEntry.LittleEndianReader
     * @param in InputStream
     * @return ZipEntry.LittleEndianReader.readShortLE(bin)
     * @throws NoSuchMethodException
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     */
    private static int readShortLE(Object ler, InputStream in) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        return callReturnMethod(class_LittleEndianReader, ler, "readShortLE", int.class,
            new Class[] { InputStream.class }, new Object[] { in });
    }

    /**
     * long readIntLE(InputStream in) throws IOException
     * @param ler instance of ZipEntry.LittleEndianReader
     * @param in InputStream
     * @return ZipEntry.LittleEndianReader.readIntLE(in)
     * @throws NoSuchMethodException
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     */
    private static long readIntLE(Object ler, InputStream in) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        return callReturnMethod(class_LittleEndianReader, ler, "readIntLE", long.class,
            new Class[] { InputStream.class }, new Object[] { in });
    }

    /**
     * static long readIntLE(RandomAccessFile raf) throws IOException
     * @param raf RandomAccessFile
     * @return ZipEntry.readIntLE(raf)
     * @throws NoSuchMethodException
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     */
    private static long readIntLE(RandomAccessFile raf) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        return callReturnMethod(ZipEntry.class, null, "readIntLE", long.class,
            new Class[] { RandomAccessFile.class }, new Object[] { raf });
    }

}
