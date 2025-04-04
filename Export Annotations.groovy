import qupath.lib.gui.QuPathGUI
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.regions.RegionRequest
import qupath.lib.roi.ROIs
import qupath.lib.geom.Point2
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.awt.Graphics2D
import java.io.File
import java.awt.Color
import java.awt.AlphaComposite

// Parameters
double downsample = 8.0  // Adjust this to control resolution
int padding = 100        // Padding in exported image (at export resolution)

// Output folder
def exportDir = new File("C:\\")
exportDir.mkdirs()

def imageData = getCurrentImageData()
def server = imageData.getServer()
def annotations = getAnnotationObjects()
def plane = qupath.lib.regions.ImagePlane.getDefaultPlane()

def path = server.getPath()

if (annotations.isEmpty()) {
    print "No annotations found!"
    return
}

// Sort annotations top to bottom, then left to right
def centroids = annotations.collectEntries {
    [(it): new Point2(it.getROI().getBoundsX() + it.getROI().getBoundsWidth()/2,
                     it.getROI().getBoundsY() + it.getROI().getBoundsHeight()/2)]
}
annotations.sort { a -> def p = centroids[a]; [p.getY(), p.getX()] }

// Desired region names
List<String> regionNames = [
    "Posterior Hippocampus",
    "Anterior Hippocampus",
    "Frontal Cortex",
    "Mid Brain",
    "Medulla"
]

int count = 1
for (annotation in annotations) {
    def roi = annotation.getROI()
    def boundsX = roi.getBoundsX()
    def boundsY = roi.getBoundsY()
    def boundsW = roi.getBoundsWidth()
    def boundsH = roi.getBoundsHeight()

    // Convert padding from downsampled to full-res
    int padFull = (int)(padding * downsample)

    int x = Math.max((int)boundsX - padFull, 0)
    int y = Math.max((int)boundsY - padFull, 0)
    int w = (int)boundsW + 2 * padFull
    int h = (int)boundsH + 2 * padFull

    def request = RegionRequest.createInstance(path, downsample, x, y, w, h, plane)
    BufferedImage img = server.readBufferedImage(request)

    // Create a transparent image
    int pad = (int)(padding)
    BufferedImage padded = new BufferedImage(img.getWidth() + 2 * pad, img.getHeight() + 2 * pad, BufferedImage.TYPE_INT_ARGB)
    Graphics2D g2d = padded.createGraphics()
    g2d.setComposite(AlphaComposite.Clear)
    g2d.fillRect(0, 0, padded.getWidth(), padded.getHeight())
    g2d.setComposite(AlphaComposite.SrcOver)
    g2d.drawImage(img, pad, pad, null)
    g2d.dispose()

    // Export with region name if available
    def regionName = (count <= regionNames.size()) ? regionNames[count - 1] : String.format("slice_%02d", count)
    def filename = regionName.replaceAll("\\s+", "_") + ".png"
    def outputFile = new File(exportDir, filename)
    ImageIO.write(padded, "PNG", outputFile)
    print "Saved: " + outputFile.getAbsolutePath()
    count++
}

print "✅ Done exporting ${count - 1} slices to ${exportDir.getAbsolutePath()}"
