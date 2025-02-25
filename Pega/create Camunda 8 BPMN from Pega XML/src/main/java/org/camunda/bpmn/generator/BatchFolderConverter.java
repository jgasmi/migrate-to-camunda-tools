package org.camunda.bpmn.generator;

import java.io.File;
import java.io.FilenameFilter;

public class BatchFolderConverter {

    /**
     * Usage: java BatchFolderConverter /path/to/xml/folder
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Please provide the folder containing XML files.");
            System.out.println("Usage: java BatchFolderConverter <folderPath>");
            return;
        }

        File folder = new File(args[0]);
        if (!folder.isDirectory()) {
            System.out.println("Given path is not a directory: " + args[0]);
            return;
        }

        // Filter only .xml files
        File[] xmlFiles = folder.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".xml");
            }
        });

        if (xmlFiles == null || xmlFiles.length == 0) {
            System.out.println("No .xml files found in folder: " + folder.getAbsolutePath());
            return;
        }

        // Loop through each XML file and convert
        for (File xmlFile : xmlFiles) {
            // Derive .bpmn file name in the same folder or choose your own pattern
            String baseName = xmlFile.getName().substring(0, xmlFile.getName().lastIndexOf("."));
            File bpmnFile = new File(folder, baseName + ".bpmn");

            try {
                // Call your refactored method
                BPMNGenFromPega.convertFile(xmlFile.getAbsolutePath(), bpmnFile.getAbsolutePath());
            } catch (Exception e) {
                System.err.println("Error converting file: " + xmlFile.getName());
                e.printStackTrace();
            }
        }

        System.out.println("Batch conversion completed.");
    }
}
