import groovy.io.FileType
import java.awt.image.BufferedImage
import qupath.lib.images.ImageData
import qupath.lib.images.servers.ImageServerProvider

import qupath.lib.gui.commands.ProjectCommands
import qupath.lib.projects.Projects
import qupath.lib.regions.ImagePlane
import qupath.imagej.processing.RoiLabeling
import qupath.lib.gui.QuPathGUI
import ij.IJ
import ij.gui.Wand
import ij.process.ImageProcessor
import qupath.lib.scripting.QP
// Remove this if you don't need to generate new cell intensity measurements (it may be quite slow)
import qupath.lib.analysis.features.ObjectMeasurements
import qupath.lib.gui.tools.MeasurementExporter
import qupath.lib.objects.PathCellObject

new QP()

//This does not have to exist, it will create a new folder for the new Project at this location
prjtDir="/Users/davidhaley/tmp/qupath-project"
// Must contain all the OME.TIFF files you want to include, is recursive (will pick up any child folder/files)
omeDir="/Users/davidhaley/tmp/qupath-project/OMETIFFs"
// Must include both sets of Labled Mask files, with proper prefixes.
masksDir="/Users/davidhaley/tmp/qupath-project/SEGMASKS"
// Will create new file, containing all ROIs
def outputPath = "" // "O:/2021/09_September/Hyperion_Bartemes/AllQuPathQuantification.tsv"

// "C:\Users\M088378\AppData\Local\QuPath-0.3.2\QuPath-0.3.2 (console).exe" script R:\PUBLIC\Hyperion_Imaging\Comfere_Nneke_MD\RaymondDevelopments\dev_code\createNewProject.groovy
def downsample = 1
double xOrigin = 0
double yOrigin = 0
ImagePlane plane = ImagePlane.getDefaultPlane()

File directory = new File(prjtDir)
if (!directory.exists())
{
    println("No project directory, creating one!")
    directory.mkdirs()
}

// Create project
def project = Projects.createProject(directory , BufferedImage.class)

// Build a list of files
def files = []
selectedDir = new File(omeDir)
selectedDir.eachFileRecurse (FileType.FILES) { file ->
    if (file.getName().toLowerCase().endsWith(".ome.tiff"))
    {
        files << file
    }
}


// Add files to the project
for (file in files) {
    String imagePath = file.getCanonicalPath()
    println(imagePath)

    // Get serverBuilder
    //def support = ImageServerProvider.getPreferredUriImageSupport(BufferedImage.class, imagePath, "") Prior to v3.0
    def support = ImageServerProvider.getPreferredUriImageSupport(BufferedImage.class, imagePath)
    def builder = support.builders.get(0)

    // Make sure we don't have null
    if (builder == null) {
        print "Image not supported: " + imagePath
        continue
    }

    // Add the image as entry to the project
    print "Adding: " + imagePath
    entry = project.addImage(builder)

    // Set a particular image type
    def imageData = entry.readImageData()
    imageData.setImageType(ImageData.ImageType.FLUORESCENCE)
    entry.saveImageData(imageData)

    // Write a thumbnail if we can
    var img = ProjectCommands.getThumbnailRGB(imageData.getServer());
    entry.setThumbnail(img)

    // Add an entry name (the filename)
    entry.setImageName(file.getName())
}

// Changes should now be reflected in the project directory
project.syncChanges()


File directoryOfMasks = new File(masksDir)
if (directoryOfMasks.exists()){
    println("Discovering Mask Files...")
    // Build 2 lists of mask files
    def nucfiles = []
    def wholecellfiles = []

    directoryOfMasks.eachFileRecurse (FileType.FILES) { file ->
        if (file.getName().endsWith("_NucleusMask.tiff"))
        { nucfiles << file }
    }
    directoryOfMasks.eachFileRecurse (FileType.FILES) { file ->
        if (file.getName().endsWith("_WholeCellMask.tiff"))
        { wholecellfiles << file }
    }

    for (entry in project.getImageList()) {
        imgName = entry.getImageName()
        String sample = imgName[imgName.lastIndexOf(':')+1..-1].tokenize(".")[0]
        println(" >>> "+sample)
        def imageData = entry.readImageData()
        def server = imageData.getServer()

        //Mask File for Nuclei
        def nMask1 = nucfiles.find { it.getName().contains(sample) }
        println("FOUND NUC MASK: "+nMask1)
        if(nMask1 == null){
            println(" >>> MISSING MASK FILES!! <<<")
            println()
            continue
        }
        def nucs = IJ.openImage(nMask1.getPath())
        //println("Bit Depth = "+nucs.getBitDepth() )
        println("[width, height, nChannels, nSlices, nFrames] = "+nucs.getDimensions() )
        // Convert labels to ImageJ ROIs
        def ipNuclei = nucs.getProcessor()
        int n1 = ipNuclei.getStatistics().max as int
        //println("Max Label Index = "+n1)
        def nucleiRois  = RoiLabeling.labelsToConnectedROIs(ipNuclei, n1)
        nucleiRois = nucleiRois - null	//.findAll{it != null}`
        println "Number of Nuclei:"+nucleiRois.size()


        //Mask File for Whole Cells
        def wMask2 = wholecellfiles.find { it.getName().contains(sample) }
        println("FOUND WC MASK: "+wMask2)
        def imp = IJ.openImage(wMask2.getPath())
        //println("Bit Depth = "+imp.getBitDepth() )
        //println("[width, height, nChannels, nSlices, nFrames] = "+imp.getDimensions() )
        def ipCells = imp.getProcessor()
        int n2 = ipCells.getStatistics().max as int
        //println("Max Label Index = "+n2)
        def cellRois  = RoiLabeling.labelsToConnectedROIs(ipCells, n2)
        println "Number of WholeCells:"+cellRois.size()

        /*
        * Add cells to Hierarchy
        */
        if( cellRois.size() == nucleiRois.size() ){
            println " >>> Laying down segmentation..."
            def pathObjects = []
            nucleiRois.eachWithIndex{ item, label ->
                if(cellRois[label] != null){
                    def roiNucQ = IJTools.convertToROI(nucleiRois[label], xOrigin, yOrigin, downsample, plane)
                    def roiCellQ = IJTools.convertToROI(cellRois[label], xOrigin, yOrigin, downsample, plane)
                    pathObjects << PathObjects.createCellObject(roiCellQ, roiNucQ, null, null)
                } else {
                    println("Null WholeCell Mask at "+label+". Skip!")
                }
            } // EOL: nucleiRois
            imageData.getHierarchy().addPathObjects(pathObjects)
            entry.saveImageData(imageData)

            print " >>> Calculating measurements..."
            print(imageData.getHierarchy())
            def measurements = ObjectMeasurements.Measurements.values() as List
            def compartments = ObjectMeasurements.Compartments.values() as List // Won't mean much if they aren't cells...
            for (detection in imageData.getHierarchy().getDetectionObjects()) {
                ObjectMeasurements.addIntensityMeasurements( server, detection, downsample, measurements, compartments )
                ObjectMeasurements.addShapeMeasurements( detection, server.getPixelCalibration(), ObjectMeasurements.ShapeFeatures.values() )
            }
            fireHierarchyUpdate()

        } else {
            println "Critical Error in Segemenation Mask count!"
        }
        entry.saveImageData(imageData)
        imageData.getServer().close() // best to do this...
    }

}

//def imagesToExport = project.getImageList()
//def separator = "\t"
//def exportType = PathCellObject.class
//def outputFile = new File(outputPath)
// Create the measurementExporter and start the export
//def exporter  = new MeasurementExporter()
//	.imageList(imagesToExport) // Images from which measurements will be exported
//	.separator(separator) // Character that separates values
//	.exportType(exportType) // Type of objects to export
//	.exportMeasurements(outputFile) // Start the export process


project.syncChanges()
println("")
println("Done.")

