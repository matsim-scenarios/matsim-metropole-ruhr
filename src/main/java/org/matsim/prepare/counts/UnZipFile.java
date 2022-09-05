package org.matsim.prepare.counts;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

class UnZipFile {

	Logger log = LogManager.getLogger(UnZipFile.class);

	File unZipFile(File src) throws IOException {
//	 log.info("trying to unzip file " + src.getName());
		String filePath = src.getAbsolutePath();
		ZipFile zFile = new ZipFile(src);
		File unzippedFolder = new File(filePath.substring(0, filePath.lastIndexOf('.')));
		unzippedFolder.mkdir();
		String newPath = filePath.substring(0, filePath.lastIndexOf('.'));

		Enumeration<? extends ZipEntry> entries = zFile.entries();
		boolean hasUnzippedOneTextFile = false;
		while (entries.hasMoreElements()) {

			ZipEntry anEntry = entries.nextElement();

			if (!anEntry.isDirectory() && ((anEntry.getName().endsWith("txt") && !hasUnzippedOneTextFile) || anEntry.getName().endsWith("xls"))) {
				saveEntry(zFile, anEntry, newPath);
				if (anEntry.getName().endsWith("txt")) hasUnzippedOneTextFile = true;
			} else if (anEntry.isDirectory()) {
				newPath += File.separator + anEntry.getName();
			}
		}

//	  log.info("done unzipping");
		return unzippedFolder;
	}

	private void saveEntry(ZipFile zFile, ZipEntry anEntry, String newPath) throws IOException {
		InputStream in = null;
		BufferedOutputStream fos = null;

		try {
			File aFile = new File(newPath + "/" + anEntry.getName());
			aFile.getParentFile().mkdirs();
			in = zFile.getInputStream(anEntry);
			fos = new BufferedOutputStream(new FileOutputStream(aFile));
			byte[] buffer = new byte[1024];
			int length;
			while ((length = in.read(buffer)) > 0) {
				fos.write(buffer, 0, length);
			}
		} finally {
			if (in != null) {
				in.close();
			}
			if (fos != null) {
				fos.close();
			}
		}

	}

}