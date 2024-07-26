import groovy.io.FileType
import ij.IJ
import ij.process.ColorProcessor
import qupath.imagej.processing.RoiLabeling
import qupath.imagej.tools.IJTools
import qupath.lib.analysis.features.ObjectMeasurements
import qupath.lib.gui.commands.ProjectCommands
import qupath.lib.images.ImageData
import qupath.lib.images.servers.ImageServerProvider
import qupath.lib.objects.PathObjects
import qupath.lib.projects.Projects
import qupath.lib.regions.ImagePlane
import qupath.lib.scripting.QP

import java.awt.image.BufferedImage

import static qupath.lib.scripting.QP.fireHierarchyUpdate
import static qupath.lib.scripting.QP.resolveHierarchy

// This forces various things to initialize e.g. registering type handlers.
//noinspection GroovyUnusedAssignment
_ = new QP()

// Remove this if you don't need to generate new cell intensity measurements (it may be quite slow)

// ome.tiff files must contain this word in their name to be included
regionSet = null

def workflowDir = "/path/to/datasetdirectory"

def omeDir = workflowDir + "/OMETIFF"
def masksDir = workflowDir + "/SEGMASKS"
def prjtDir = workflowDir + "/QUPATH"
def outputPath = workflowDir + "/REPORTS/AllQuPathQuantification.tsv"

println("  Input OME.TIFFs: " + omeDir)
println("  Input Labelled Masks: " + masksDir)
println("  Output QuPath: " + prjtDir)
println("  Quantifications: " + outputPath)

def downsample = 1
double xOrigin = 0
double yOrigin = 0
ImagePlane plane = ImagePlane.getDefaultPlane()

File directory = new File(prjtDir)
if (!directory.exists()) {
    println("No project directory, creating one!")
    directory.mkdirs()
}

// Create project
def project = Projects.createProject(directory, BufferedImage.class)

// Build a list of files
def files = []
selectedDir = new File(omeDir)
selectedDir.eachFileRecurse(FileType.FILES) { file ->
    if (file.getName().toLowerCase().endsWith(".ome.tiff")) {
        if (regionSet == null || file.getName().contains(regionSet)) {
            files << file
        }
    }
}

println('---')

// Add files to the project
for (file in files) {
    String imagePath = file.getCanonicalPath()
    println(imagePath)

    // Get serverBuilder
    def support = ImageServerProvider.getPreferredUriImageSupport(BufferedImage.class, imagePath)
    //println(support)
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
if (directoryOfMasks.exists()) {
    println("Discovering Mask Files...")
    File[] wholecellfiles = []
    directoryOfMasks.eachFileRecurse(FileType.FILES) { file ->
        if (file.getName().endsWith("_WholeCellMask.tiff")) {
            wholecellfiles << file
        }
    }

    for (entry in project.getImageList()) {
        imgName = entry.getImageName()
        String sample = imgName[imgName.lastIndexOf(':') + 1..-1].tokenize(".")[0]
        println(" >>> " + sample)
        def imageData = entry.readImageData()
        def server = imageData.getServer()

        //Mask File for whole cell
        def wholeCellMask1 = wholecellfiles.find { it.getName().contains(sample) }
        if (wholeCellMask1 == null) {
            println(" >>> MISSING MASK FILES!! <<<")
            println()
            continue
        }

        def imp = IJ.openImage(wholeCellMask1.absolutePath)
        print(imp)
        int n = imp.getStatistics().max as int
        println("   Max Cell Label: " + n)
        if (n == 0) {
            print 'No objects found!'
            return
        }
        def ip = imp.getProcessor()
        if (ip instanceof ColorProcessor) {
            throw new IllegalArgumentException("RGB images are not supported!")
        }
        def roisIJ = RoiLabeling.labelsToConnectedROIs(ip, n)
        def rois = roisIJ.collect {
            if (it == null)
                return null
            return IJTools.convertToROI(it, 0, 0, downsample, plane);
        }
        rois = rois.findAll { null != it }
        // Convert QuPath ROIs to objects
        def pathObjects = rois.collect {
            return PathObjects.createDetectionObject(it)
        }
        println("   Number of PathObjects: " + pathObjects.size())
        imageData.getHierarchy().addPathObjects(pathObjects)
        resolveHierarchy()
        entry.saveImageData(imageData)

        println(" >>> Calculating measurements...")
        println(imageData.getHierarchy())
        println("  DetectionObjects:" + imageData.getHierarchy().getDetectionObjects().size())
        def measurements = ObjectMeasurements.Measurements.values() as List
        println(measurements)
        for (detection in imageData.getHierarchy().getDetectionObjects()) {
            ObjectMeasurements.addIntensityMeasurements(server, detection, downsample, measurements, [])
            ObjectMeasurements.addShapeMeasurements(detection, server.getPixelCalibration(), ObjectMeasurements.ShapeFeatures.values())
        }
        fireHierarchyUpdate()
        entry.saveImageData(imageData)
        imageData.getServer().close() // best to do this...
    }

}

project.syncChanges()

println("")
println("Done.")
