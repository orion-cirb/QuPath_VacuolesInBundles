import org.apache.commons.io.FilenameUtils
import static qupath.lib.scripting.QP.*
import qupath.lib.objects.*

// Load image
def imageDir = new File(project.getImageList()[0].getUris()[0]).getParent()
def imageName = getCurrentImageData().getServer().getMetadata().getName()
def imgNameWithOutExt = FilenameUtils.removeExtension(imageName)

// Delete all annotations
clearDetections()

def vacuoleClass = classes.PathClassFactory.getPathClass('vacuole', makeRGB(255,0,0))
def bundleClass = classes.PathClassFactory.getPathClass('bundle', makeRGB(0,255,0))

// Find annotations files for current image
def p = ~/${imgNameWithOutExt}.*\.annot/
def resultsDir = new File(buildFilePath(imageDir+'/../Results'))
resultsDir.eachFileMatch(p) {file ->
    new File(file.path).withObjectInputStream {
        def detections = it.readObject()
        def vacuoles = detections.findAll{it.getPathClass() == vacuoleClass}
        def bundles = detections.findAll{it.getPathClass() == bundleClass}
        print('Adding detections ' + bundles.toString())
        addObjects(bundles)
        print('Adding detections ' + vacuoles.toString())
        addObjects(vacuoles)
    }
}
