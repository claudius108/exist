package org.exist.repo;

import org.exist.dom.persistent.BinaryDocument;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.persistent.LockedDocument;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.DBBroker;
import org.exist.storage.lock.Lock.LockMode;
import org.exist.storage.txn.Txn;
import org.exist.util.FileUtils;
import org.exist.util.io.TemporaryFileManager;
import org.exist.xmldb.XmldbURI;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Utility methods for backing up and restoring the expath file system repository.
 */
public class RepoBackup {

    public final static String REPO_ARCHIVE = "expathrepo.zip";

    public static Path backup(final DBBroker broker) throws IOException {
        final TemporaryFileManager temporaryFileManager = TemporaryFileManager.getInstance();
        final Path tempFile = temporaryFileManager.getTemporaryFile();
        try(final ZipOutputStream os = new ZipOutputStream(Files.newOutputStream(tempFile))) {
            final Path directory = ExistRepository.getRepositoryDir(broker.getConfiguration());
            zipDir(directory.toAbsolutePath(), os, "");
        }
        return tempFile;
    }

    /**
     * @deprecated Use {@link #restore(Txn, DBBroker)}.
     */
    @Deprecated
    public static void restore(final DBBroker broker) throws IOException, PermissionDeniedException {
        restore(null, broker);
    }

    public static void restore(final Txn transaction, final DBBroker broker) throws IOException, PermissionDeniedException {
        final XmldbURI docPath = XmldbURI.createInternal(XmldbURI.ROOT_COLLECTION + "/" + REPO_ARCHIVE);
        try(final LockedDocument lockedDoc = broker.getXMLResource(docPath, LockMode.READ_LOCK)) {
            if (lockedDoc == null) {
                return;
            }

            final DocumentImpl doc = lockedDoc.getDocument();
            if (doc.getResourceType() != DocumentImpl.BINARY_FILE) {
                throw new IOException(docPath + " is not a binary resource");
            }

            try (final InputStream is = broker.getBrokerPool().getBlobStore().get(transaction,  ((BinaryDocument)doc).getBlobId())) {
                final Path directory = ExistRepository.getRepositoryDir(broker.getConfiguration());
                unzip(doc.getURI(), is, directory);
            }
        }
    }

    /**
     * Zip up a directory path
     *
     * @param directory
     * @param zos
     * @param path
     * @throws IOException
     */
    public static void zipDir(final Path directory, final ZipOutputStream zos, final String path) throws IOException {
        // get a listing of the directory content
        final List<Path> dirList = FileUtils.list(directory);

        // loop through dirList, and zip the files
        for (final Path f : dirList) {
            if (Files.isDirectory(f)) {
                zipDir(f, zos, path + FileUtils.fileName(f) + "/");
                continue;
            }

            final ZipEntry anEntry = new ZipEntry(path + FileUtils.fileName(f));
            zos.putNextEntry(anEntry);
            Files.copy(f, zos);
        }
    }

    /***
     * Extract zipfile to outdir with complete directory structure.
     *
     * @param fileUri the file URI
     * @param file Input .zip file
     * @param outdir Output directory
     */
    public static void unzip(final XmldbURI fileUri, final InputStream file, final Path outdir) throws IOException {
        try (final ZipInputStream zin = new ZipInputStream(file)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                final String name = entry.getName();
                final Path out = outdir.resolve(name);

                if (!out.startsWith(outdir)) {
                    throw new IOException("Detected archive exit attack! zipFile=" + fileUri.getRawCollectionPath() + ", entry=" + name + ", outdir=" + outdir.toAbsolutePath().normalize().toString());
                }

                if (entry.isDirectory() ) {
                    Files.createDirectories(outdir.resolve(name));
                    continue;
                }

                final String dir = dirpart(name);
                if (dir != null) {
                    Files.createDirectories(outdir.resolve(name));
                }

                //extract file
                Files.copy(zin, outdir.resolve(name));
            }
        }
    }

    private static String dirpart(final String name) {
        final int s = name.lastIndexOf(File.separatorChar);
        return s == -1 ? null : name.substring( 0, s );
    }
}
