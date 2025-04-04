/**
 * Automatically group close annotations and create a convex hull from them in QuPath v0.6.0.
 *
 * Compatible with JTS used in QuPath v0.6.x.
 */

import org.locationtech.jts.algorithm.ConvexHull
import org.locationtech.jts.geom.GeometryFactory
import qupath.lib.gui.QuPathGUI
import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass
import qupath.lib.objects.PathObjectTools
import qupath.lib.roi.GeometryTools
import qupath.lib.geom.Point2

// Parameters
int targetCount = 5
boolean allowHoles = false

double distanceThreshold = 50.0  // Distance in pixels to consider for merging annotations
PathClass pathClass = PathClass.fromString("Group")

// Desired final annotation names in custom spatial order
List<String> regionNames = [
    "Posterior Hippocampus",
    "Anterior Hippocampus",
    "Frontal Cortex",
    "Mid Brain",
    "Medulla"
]

// Get all annotations from the current hierarchy
def viewer = QuPathGUI.getInstance().getViewer()
def hierarchy = viewer.getHierarchy()
def annotations = hierarchy.getAnnotationObjects()

if (annotations.isEmpty()) {
    print "No annotations found"
    return
}

// Ask user if they want to delete original annotations
boolean deleteOriginals = Dialogs.showYesNoDialog("Delete originals?", "Do you want to delete the original annotations after grouping?")

// Cluster annotations based on improved centroid k-means-style approach
def centroids = annotations.collectEntries { [(it): centroid(it)] }
List<List<PathObject>> clusters = []

// Initialize clusters with each annotation in its own cluster
annotations.each { clusters << [it] }

// Iteratively merge clusters until exactly targetCount remain
while (clusters.size() > targetCount) {
    double minDist = Double.MAX_VALUE
    int mergeIdxA = -1
    int mergeIdxB = -1

    for (i in 0..<clusters.size()) {
        def centerA = clusterCentroid(clusters[i], centroids)
        for (j in i+1..<clusters.size()) {
            def centerB = clusterCentroid(clusters[j], centroids)
            def dist = centerA.distance(centerB)
            if (dist < minDist && dist <= distanceThreshold) {
                minDist = dist
                mergeIdxA = i
                mergeIdxB = j
            }
        }
    }

    if (mergeIdxA == -1 || mergeIdxB == -1) {
        // If no clusters are close enough, force-merge closest pair regardless of threshold
        for (i in 0..<clusters.size()) {
            def centerA = clusterCentroid(clusters[i], centroids)
            for (j in i+1..<clusters.size()) {
                def centerB = clusterCentroid(clusters[j], centroids)
                def dist = centerA.distance(centerB)
                if (dist < minDist) {
                    minDist = dist
                    mergeIdxA = i
                    mergeIdxB = j
                }
            }
        }
    }

    def merged = clusters[mergeIdxA] + clusters[mergeIdxB]
    clusters.remove(mergeIdxB)
    clusters.remove(mergeIdxA)
    clusters << merged
}

// If we ever have too few clusters, split the largest cluster by centroid
while (clusters.size() < targetCount) {
    clusters.sort { -it.size() }
    def toSplit = clusters.remove(0)
    toSplit.sort { a -> centroid(a).x + centroid(a).y }
    def midpoint = (int)(toSplit.size() / 2)
    clusters << toSplit.subList(0, midpoint)
    clusters << toSplit.subList(midpoint, toSplit.size())
}

println "Final grouping into ${clusters.size()} clusters."

// Optionally remove original annotations
if (deleteOriginals) {
    hierarchy.removeObjects(annotations, false)
}

// Sort clusters based on their top-left coordinate (y first, then x)
clusters.sort { cluster ->
    def c = clusterCentroid(cluster, centroids)
    return [c.y, c.x] // top to bottom, then left to right
}

// Process each cluster
for (int i = 0; i < clusters.size(); i++) {
    def group = clusters[i]
    def geometries = group.collect { it.getROI().getGeometry() }
    def geometry = GeometryTools.union(geometries)
    if (geometry == null || geometry.isEmpty()) {
        println "Could not compute geometry for group"
        continue
    }

    // Create convex hull
    def convexHull = new ConvexHull(geometry.getCoordinates(), new GeometryFactory())
    def output = convexHull.getConvexHull()
    if (!allowHoles)
        output = GeometryTools.fillHoles(output)

    def plane = group.iterator().next().getROI().getImagePlane()
    def className = i < regionNames.size() ? regionNames[i] : "Group ${i+1}"
    def annotation = PathObjects.createAnnotationObject(
            GeometryTools.geometryToROI(output, plane),
            PathClass.fromString(className)
    )
    hierarchy.addObject(annotation)
}

// Utility function to get centroid
Point2 centroid(PathObject obj) {
    def bounds = obj.getROI().getGeometry().getEnvelopeInternal()
    return new Point2((bounds.getMinX() + bounds.getMaxX()) / 2, (bounds.getMinY() + bounds.getMaxY()) / 2)
}

// Get centroid of a cluster
Point2 clusterCentroid(List<PathObject> objs, Map<PathObject, Point2> centroids) {
    double x = 0, y = 0
    objs.each {
        def p = centroids[it]
        x += p.x
        y += p.y
    }
    return new Point2(x / objs.size(), y / objs.size())
}
