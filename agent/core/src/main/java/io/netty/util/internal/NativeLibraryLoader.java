/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.internal;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * Helper class to load JNI resources.
 *
 */
// this file is from netty 4.1.16.Final, patched to include fix from
// https://github.com/netty/netty/pull/7345
// this is just temporary until the release of grpc-java that uses netty 4.1.17.Final or above
public final class NativeLibraryLoader {

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(NativeLibraryLoader.class);

    private static final String NATIVE_RESOURCE_HOME = "META-INF/native/";
    private static final File WORKDIR;
    private static final boolean DELETE_NATIVE_LIB_AFTER_LOADING;

    static {
        String workdir = SystemPropertyUtil.get("io.netty.native.workdir");
        if (workdir != null) {
            File f = new File(workdir);
            f.mkdirs();

            try {
                f = f.getAbsoluteFile();
            } catch (Exception ignored) {
                // Good to have an absolute path, but it's OK.
            }

            WORKDIR = f;
            logger.debug("-Dio.netty.native.workdir: " + WORKDIR);
        } else {
            WORKDIR = PlatformDependent.tmpdir();
            logger.debug("-Dio.netty.native.workdir: " + WORKDIR + " (io.netty.tmpdir)");
        }

        DELETE_NATIVE_LIB_AFTER_LOADING = SystemPropertyUtil.getBoolean(
                "io.netty.native.deleteLibAfterLoading", true);
    }

    /**
     * Loads the first available library in the collection with the specified {@link ClassLoader}.
     *
     * @throws IllegalArgumentException
     *             if none of the given libraries load successfully.
     */
    public static void loadFirstAvailable(ClassLoader loader, String... names) {
        for (String name : names) {
            try {
                load(name, loader);
                return;
            } catch (Throwable t) {
                logger.debug("Unable to load the library '{}', trying next name...", name, t);
            }
        }
        throw new IllegalArgumentException("Failed to load any of the given libraries: "
                + Arrays.toString(names));
    }

    /**
     * The shading prefix added to this class's full name.
     *
     * @throws UnsatisfiedLinkError
     *             if the shader used something other than a prefix
     */
    private static String calculatePackagePrefix() {
        String maybeShaded = NativeLibraryLoader.class.getName();
        // Use ! instead of . to avoid shading utilities from modifying the string
        String expected = "io!netty!util!internal!NativeLibraryLoader".replace('!', '.');
        if (!maybeShaded.endsWith(expected)) {
            throw new UnsatisfiedLinkError(String.format(
                    "Could not find prefix added to %s to get %s. When shading, only adding a "
                            + "package prefix is supported",
                    expected, maybeShaded));
        }
        return maybeShaded.substring(0, maybeShaded.length() - expected.length());
    }

    /**
     * Load the given library with the specified {@link ClassLoader}
     */
    public static void load(String originalName, ClassLoader loader) {
        // Adjust expected name to support shading of native libraries.
        String name = calculatePackagePrefix().replace('.', '_') + originalName;

        try {
            // first try to load from java.library.path
            loadLibrary(loader, name, false);
            return;
        } catch (Throwable ex) {
            logger.debug(
                    "{} cannot be loaded from java.libary.path, "
                            + "now trying export to -Dio.netty.native.workdir: {}",
                    name, WORKDIR, ex);
        }

        String libname = System.mapLibraryName(name);
        String path = NATIVE_RESOURCE_HOME + libname;

        InputStream in = null;
        OutputStream out = null;
        File tmpFile = null;
        URL url;
        if (loader == null) {
            url = ClassLoader.getSystemResource(path);
        } else {
            url = loader.getResource(path);
        }
        try {
            if (url == null) {
                if (PlatformDependent.isOsx()) {
                    String fileName = path.endsWith(".jnilib")
                            ? NATIVE_RESOURCE_HOME + "lib" + name + ".dynlib"
                            : NATIVE_RESOURCE_HOME + "lib" + name + ".jnilib";
                    if (loader == null) {
                        url = ClassLoader.getSystemResource(fileName);
                    } else {
                        url = loader.getResource(fileName);
                    }
                    if (url == null) {
                        throw new FileNotFoundException(fileName);
                    }
                } else {
                    throw new FileNotFoundException(path);
                }
            }

            int index = libname.lastIndexOf('.');
            String prefix = libname.substring(0, index);
            String suffix = libname.substring(index, libname.length());

            tmpFile = File.createTempFile(prefix, suffix, WORKDIR);
            in = url.openStream();
            out = new FileOutputStream(tmpFile);

            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
            out.flush();

            // Close the output stream before loading the unpacked library,
            // because otherwise Windows will refuse to load it when it's in use by other process.
            closeQuietly(out);
            out = null;

            loadLibrary(loader, tmpFile.getPath(), true);
        } catch (UnsatisfiedLinkError e) {
            try {
                if (tmpFile != null && tmpFile.isFile() && tmpFile.canRead() &&
                        !NoexecVolumeDetector.canExecuteExecutable()) {
                    logger.info(
                            "{} exists but cannot be executed even when execute permissions set; " +
                                    "check volume for \"noexec\" flag; use -Dio.netty.native.workdir=[path] "
                                    +
                                    "to set native working directory separately.",
                            tmpFile.getPath());
                }
            } catch (Throwable t) {
                logger.debug("Error checking if {} is on a file store mounted with noexec", tmpFile,
                        t);
            }
            // Re-throw to fail the load
            throw e;
        } catch (Exception e) {
            throw (UnsatisfiedLinkError) new UnsatisfiedLinkError(
                    "could not load a native library: " + name).initCause(e);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            // After we load the library it is safe to delete the file.
            // We delete the file immediately to free up resources as soon as possible,
            // and if this fails fallback to deleting on JVM exit.
            if (tmpFile != null && (!DELETE_NATIVE_LIB_AFTER_LOADING || !tmpFile.delete())) {
                tmpFile.deleteOnExit();
            }
        }
    }

    /**
     * Loading the native library into the specified {@link ClassLoader}.
     * 
     * @param loader
     *            - The {@link ClassLoader} where the native library will be loaded into
     * @param name
     *            - The native library path or name
     * @param absolute
     *            - Whether the native library will be loaded by path or by name
     */
    private static void loadLibrary(final ClassLoader loader, final String name,
            final boolean absolute) {
        try {
            // Make sure the helper is belong to the target ClassLoader.
            final Class<?> newHelper = tryToLoadClass(loader, NativeLibraryUtil.class);
            loadLibraryByHelper(newHelper, name, absolute);
            logger.debug("Successfully loaded the library {}", name);
            return;
        } catch (UnsatisfiedLinkError e) { // Should by pass the UnsatisfiedLinkError here!
            logger.debug("Unable to load the library '{}', trying other loading mechanism.", name,
                    e);
        } catch (Exception e) {
            logger.debug("Unable to load the library '{}', trying other loading mechanism.", name,
                    e);
        }
        NativeLibraryUtil.loadLibrary(name, absolute); // Fallback to local helper class.
        logger.debug("Successfully loaded the library {}", name);
    }

    private static void loadLibraryByHelper(final Class<?> helper, final String name,
            final boolean absolute)
            throws UnsatisfiedLinkError {
        Object ret = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    // Invoke the helper to load the native library, if succeed, then the native
                    // library belong to the specified ClassLoader.
                    Method method = helper.getMethod("loadLibrary", String.class, boolean.class);
                    method.setAccessible(true);
                    return method.invoke(null, name, absolute);
                } catch (Exception e) {
                    return e;
                }
            }
        });
        if (ret instanceof Throwable) {
            Throwable error = (Throwable) ret;
            Throwable cause = error.getCause();
            if (cause != null) {
                if (cause instanceof UnsatisfiedLinkError) {
                    throw (UnsatisfiedLinkError) cause;
                } else {
                    throw new UnsatisfiedLinkError(cause.getMessage());
                }
            }
            throw new UnsatisfiedLinkError(error.getMessage());
        }
    }

    /**
     * Try to load the helper {@link Class} into specified {@link ClassLoader}.
     * 
     * @param loader
     *            - The {@link ClassLoader} where to load the helper {@link Class}
     * @param helper
     *            - The helper {@link Class}
     * @return A new helper Class defined in the specified ClassLoader.
     * @throws ClassNotFoundException
     *             Helper class not found or loading failed
     */
    private static Class<?> tryToLoadClass(final ClassLoader loader, final Class<?> helper)
            throws ClassNotFoundException {
        try {
            return Class.forName(helper.getName(), false, loader);
        } catch (ClassNotFoundException e) {
            if (loader == null) {
                // cannot defineClass inside bootstrap class loader
                throw e;
            }
            // The helper class is NOT found in target ClassLoader, we have to define the helper
            // class.
            final byte[] classBinary = classToByteArray(helper);
            return AccessController.doPrivileged(new PrivilegedAction<Class<?>>() {
                @Override
                public Class<?> run() {
                    try {
                        // Define the helper class in the target ClassLoader,
                        // then we can call the helper to load the native library.
                        Method defineClass =
                                ClassLoader.class.getDeclaredMethod("defineClass", String.class,
                                        byte[].class, int.class, int.class);
                        defineClass.setAccessible(true);
                        return (Class<?>) defineClass.invoke(loader, helper.getName(), classBinary,
                                0,
                                classBinary.length);
                    } catch (Exception e) {
                        throw new IllegalStateException("Define class failed!", e);
                    }
                }
            });
        }
    }

    /**
     * Load the helper {@link Class} as a byte array, to be redefined in specified
     * {@link ClassLoader}.
     * 
     * @param clazz
     *            - The helper {@link Class} provided by this bundle
     * @return The binary content of helper {@link Class}.
     * @throws ClassNotFoundException
     *             Helper class not found or loading failed
     */
    private static byte[] classToByteArray(Class<?> clazz) throws ClassNotFoundException {
        String fileName = clazz.getName();
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) {
            fileName = fileName.substring(lastDot + 1);
        }
        URL classUrl = clazz.getResource(fileName + ".class");
        if (classUrl == null) {
            throw new ClassNotFoundException(clazz.getName());
        }
        byte[] buf = new byte[1024];
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        InputStream in = null;
        try {
            in = classUrl.openStream();
            for (int r; (r = in.read(buf)) != -1;) {
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new ClassNotFoundException(clazz.getName(), ex);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignore) {
                // ignore
            }
        }
    }

    private NativeLibraryLoader() {
        // Utility
    }

    private static final class NoexecVolumeDetector {

        private NoexecVolumeDetector() {}

        private static boolean canExecuteExecutable() {
            // Pre-JDK7, the Java API did not directly support POSIX permissions; instead of
            // implementing a custom work-around, assume true, which disables the check.
            return true;
        }
    }
}
