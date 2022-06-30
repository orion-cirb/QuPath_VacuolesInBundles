import qupath.lib.objects.*
import qupath.lib.roi.*
import static qupath.lib.gui.scripting.QPEx.*
import org.apache.commons.io.FilenameUtils

// Open project
def project = getProject()
def pathProject = buildFilePath(PROJECT_BASE_DIR)

// Load classifier
def pathClassifier = buildFilePath(pathProject, 'classifiers/pixel_classifier/bundle_vacuole.json')
if (pathClassifier == null)
    println 'No pixel classifier found'
def classifier = loadPixelClassifier('bundle_vacuole')

// Create Results directory
def imageDir = new File(project.getImageList()[0].getUris()[0]).getParent()
def resultsDir = buildFilePath(imageDir, '../Results')
if (!fileExists(resultsDir)) mkdirs(resultsDir)

// Create Global results file and write headers
def globalResultsFile = new File(buildFilePath(resultsDir, 'Global results.csv'))
globalResultsFile.createNewFile()
def globalResHeaders = 'Image Name\tImage or ROI area (um2)\tNb bundles\tTotal area bundles\tMean area bundles\tStd area bundles\tNb vacuoles in bundles\tTotal area vacuoles\tMean area vacuoles\tStd area vacuoles\n'
globalResultsFile.write(globalResHeaders)

// Create Detailed results file and write headers
def detailedResultsFile = new File(buildFilePath(resultsDir, 'Detailed results.csv'))
detailedResultsFile.createNewFile()
def detailedResHeaders = 'Image Name\tBundle ID\tArea bundle\tNb vacuoles in bundle\tTotal area vacuoles\tMean area vacuoles\tStd area vacuoles\n'
detailedResultsFile.write(detailedResHeaders)

// Create bundles and vacuoles classes
def vacuoleClass = classes.PathClassFactory.getPathClass('vacuole')
def bundleClass = classes.PathClassFactory.getPathClass('bundle')

// Get objects areas
def getObjectsAreas(objects, pixelWidth) {
    def areas = []
    def nbObjects = objects.size()
    if (nbObjects) {
        for (obj in objects) {
            areas << obj.getROI().getScaledArea(pixelWidth, pixelWidth)
        }
    }
    return areas
}

// Get std of values
def getStd(values, mean) {
    def std = 0
    def nbValues = values.size()
    if (nbValues) {
        for (val in values) {
            std += (val-mean)**2
        }
    }
    std = Math.sqrt(std / nbValues)
    return std
}

// Save detections
def saveDetections(imgName) {
    def path = buildFilePath(imgName + '.annot')
    new File(path).withObjectOutputStream {
        it.writeObject(getDetectionObjects())
    }
    println 'Detections saved...' + '\n'
}

// Loop over images in project
for (entry in project.getImageList()) {
    def imageData = entry.readImageData()
    def server = imageData.getServer()
    def cal = server.getPixelCalibration()
    def pixelWidth = cal.getPixelWidth().doubleValue()
    def pixelUnit = cal.getPixelWidthUnit()
    def imgName = entry.getImageName()
    def imgNameWithOutExt = FilenameUtils.removeExtension(imgName)

    println 'Analysing image ' + imgNameWithOutExt
    setBatchProjectAndImage(project, imageData)
    setImageType('FLUORESCENCE')

    // Find ROI
    def annotation = getAnnotationObjects()
    if (annotation.isEmpty()) println 'No ROI provided, the analysis will be performed in the entire image'
    else println 'ROI provided, the analysis will be performed in it'

    selectAnnotations();
    classifyDetectionsByCentroid(classifier)
    resetSelection()
    selectAnnotations();
    createDetectionsFromPixelClassifier(classifier, 0, 0, 'SPLIT', 'DELETE_EXISTING')
    selectDetections()
    addPixelClassifierMeasurements('bundle_vacuole', 'bundle_vacuole')

    println  'Nb vacuoles detected ' + getDetectionObjects().findAll{it.getPathClass() == vacuoleClass}.size()
    println  'Nb bundles detected ' + getDetectionObjects().findAll{it.getPathClass() == bundleClass}.size()

    def allVacuoles = getDetectionObjects().findAll{it.getPathClass() == vacuoleClass &&
            measurement(it, 'bundle_vacuole: vacuole area µm^2') > 1}
    def unfilledBundles = getDetectionObjects().findAll{it.getPathClass() == bundleClass &&
            measurement(it, 'bundle_vacuole: bundle area µm^2') > 100}

    println  'Nb vacuoles after filtering ' + allVacuoles.size()
    println  'Nb bundles after filtering ' + unfilledBundles.size()

    def vacuoles = []
    def bundles = []
    for (bundle in unfilledBundles) {
        def bundleROI = RoiTools.fillHoles(bundle.getROI())
        bundles << new PathDetectionObject(bundleROI, bundleClass)
        def vacuolesAreas = []
        for (vacuole in allVacuoles) {
            def vacuoleROI = vacuole.getROI()
            if (bundleROI.getConvexHull().contains(vacuoleROI.getCentroidX(), vacuoleROI.getCentroidY())) {
                vacuoles << new PathDetectionObject(vacuoleROI, vacuoleClass)
                vacuolesAreas.add(vacuoleROI.getScaledArea(pixelWidth, pixelWidth))
            }
        }

        def vacuolesTotalArea = Double.NaN
        def vacuolesMeanArea = Double.NaN
        def vacuolesStdArea = Double.NaN
        if (vacuolesAreas.size() != 0) {
            vacuolesTotalArea = vacuolesAreas.sum()
            vacuolesMeanArea = vacuolesAreas.average()
            vacuolesStdArea = getStd(vacuolesAreas, vacuolesMeanArea)
        }
        def detailedResults = imgNameWithOutExt + '\t' + bundles.size() + '\t' + bundleROI.getScaledArea(pixelWidth, pixelWidth) + '\t' + vacuolesAreas.size() + '\t' + vacuolesTotalArea + '\t' + vacuolesMeanArea + '\t' +  vacuolesStdArea + '\n'
        detailedResultsFile << detailedResults
    }


    println 'Nb vacuoles in bundles ' + vacuoles.size()

    def area = null
    if(annotation.isEmpty()) {
        area = server.getHeight()*server.getWidth()*pixelWidth*pixelWidth
    } else {
        area = annotation.getAt(0).getROI().getScaledArea(pixelWidth, pixelWidth)
    }
    def vacuolesAreas = getObjectsAreas(vacuoles, pixelWidth)
    def vacuolesMeanArea = vacuolesAreas.average()
    def bundlesAreas = getObjectsAreas(bundles, pixelWidth)
    def bundlesMeanArea = bundlesAreas.average()
    def globalResults = imgNameWithOutExt + '\t' + area + '\t' + bundles.size() + '\t' + bundlesAreas.sum() + '\t' + bundlesMeanArea + '\t' + getStd(bundlesAreas, bundlesMeanArea) + '\t' + vacuoles.size() + '\t' + vacuolesAreas.sum() + '\t' + vacuolesMeanArea  + '\t' + getStd(vacuolesAreas, vacuolesMeanArea) + '\n'
    globalResultsFile << globalResults

    clearAnnotations()
    clearDetections()
    addObjects(bundles)
    addObjects(vacuoles)
    resolveHierarchy()
    saveDetections(buildFilePath(resultsDir, imgNameWithOutExt))
}