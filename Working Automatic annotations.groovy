import qupath.lib.objects.PathAnnotationObject
import qupath.lib.roi.ROIs
import qupath.lib.regions.ImagePlane
import qupath.lib.regions.RegionRequest
import ij.IJ
import ij.ImagePlus
import ij.plugin.frame.RoiManager

// Clear existing annotations
clearAnnotations()

def imageData = getCurrentImageData()
def server = imageData.getServer()
def plane = ImagePlane.getDefaultPlane()

// --- Resolution selection ---
// Get available resolution levels as a list
def levels = server.getMetadata().getLevels()
int maxLevels = levels.size()
// Use level 2 if available, otherwise the finest available
int resolutionIndex = Math.min(2, maxLevels - 1)
double downsample = server.getMetadata().getDownsampleForLevel(resolutionIndex)

// --- Read a downsampled image for analysis ---
def request = RegionRequest.createInstance(server, downsample)
def bufferedImage = server.readBufferedImage(request)
def width = bufferedImage.getWidth()
def height = bufferedImage.getHeight()

// --- Process image with ImageJ ---
// Create an ImagePlus from the downsampled image
def imp = new ImagePlus("Downsampled", bufferedImage)
// For an inverted slide (black background, bright tissue), pixels above 50 are tissue.
IJ.setThreshold(imp, 30, 255)
// Convert to binary mask
IJ.run(imp, "Convert to Mask", "")
// Analyze particles to detect connected regions; adjust "size" parameter as needed.
IJ.run(imp, "Analyze Particles...", "size=200000-Infinity clear add")

// --- Retrieve ROIs from ImageJ's ROI Manager ---
def rm = RoiManager.getInstance()
if (rm == null) {
    print "No ROI Manager found; no regions detected."
    return
}
int n = rm.getCount()
if (n == 0) {
    print "No tissue regions detected after analysis."
    return
}
print "Detected " + n + " regions."

// --- Helper function to convert and scale an ImageJ PolygonRoi to a QuPath ROI ---
def convertAndScalePolygonRoi(ijroi, plane, scale) {
    if (ijroi instanceof ij.gui.PolygonRoi) {
        def poly = ijroi.getFloatPolygon()
        int np = poly.npoints
        double[] xpoints = new double[np]
        double[] ypoints = new double[np]
        for (int i = 0; i < np; i++) {
            xpoints[i] = poly.xpoints[i] * scale
            ypoints[i] = poly.ypoints[i] * scale
        }
        return ROIs.createPolygonROI(xpoints, ypoints, plane)
    } else {
        return null
    }
}

// --- Convert each detected ROI from the ROI Manager and add it as an annotation ---
for (int i = 0; i < n; i++) {
    def ijroi = rm.getRoi(i)
    def qproi = convertAndScalePolygonRoi(ijroi, plane, downsample)
    if (qproi == null) {
        print "Skipping non-polygon ROI at index " + i
        continue
    }
    def annotation = new PathAnnotationObject(qproi)
    annotation.setName("Slice " + (i + 1))
    addObject(annotation)
}

// Clear the ROI Manager for cleanup
rm.reset()

print "Annotations added for " + n + " brain slices."
