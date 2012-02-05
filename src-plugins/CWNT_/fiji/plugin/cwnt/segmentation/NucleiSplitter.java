package fiji.plugin.cwnt.segmentation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import mpicbg.imglib.algorithm.MultiThreadedBenchmarkAlgorithm;
import mpicbg.imglib.cursor.LocalizableCursor;
import mpicbg.imglib.labeling.Labeling;
import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.imglib.type.label.FakeType;
import mpicbg.imglib.util.Util;

import org.apache.commons.math.stat.clustering.Cluster;
import org.apache.commons.math.stat.clustering.KMeansPlusPlusClusterer;

import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotImp;

public class NucleiSplitter extends MultiThreadedBenchmarkAlgorithm {

	private static final boolean DEBUG = false;

	/** The labelled image contained the nuclei to split. */
	private final Labeling<Integer> source;

	/**
	 * Nuclei volume top threshold. Nuclei with a volume larger than this
	 * threshold will be discarded and not considered for splitting.
	 */
	private long volumeThresholdUp = 1000; // Bhavna code
	/**
	 * Nuclei volume bottom threshold. Nuclei with a volume smaller than this
	 * threshold will be discarded and not considered for splitting.
	 */
	private long volumeThresholdBottom = 0; // Bhavna code
	/**
	 * Volume selectivity: nuclei with a volume larger than mean + this factor
	 * times the standard deviation will be considered for splitting.
	 */
	private double stdFactor = 0.5;

	private ArrayList<Integer> nucleiToSplit;

	private ArrayList<Integer> thrashedLabels;

	private final List<Spot> spots;

	private final float[] calibration;

	/** The physical volume of a voxel. */
	private final float voxelVolume;

	/*
	 * CONSTRUCTOR
	 */

	public NucleiSplitter(Labeling<Integer> source, float[] calibration) {
		super();
		this.source = source;
		this.calibration = calibration;
		this.spots = Collections.synchronizedList(new ArrayList<Spot>((int) 1.5
				* source.getLabels().size()));
		this.voxelVolume = calibration[0] * calibration[1] * calibration[2];
	}

	/*
	 * METHODS
	 */

	public List<Spot> getResult() {
		return spots;
	}

	@Override
	public boolean checkInput() {
		return true;
	}

	@Override
	public boolean process() {

		if (DEBUG) {
			System.out
					.println("[NucleiSplitter] Starting nuclei splitting with calibration: "
							+ Util.printCoordinates(source.getCalibration()));
		}

		long start = System.currentTimeMillis();

		getVolumeEstimate(); // Harvest non-suspicious spots

		Thread[] threads = new Thread[numThreads];
		final AtomicInteger ai = new AtomicInteger();

		for (int i = 0; i < threads.length; i++) {
			threads[i] = new Thread("Nuclei splitter thread " + i) {
				public void run() {
					int targetNucleiNumber;
					Integer label;
					for (int j = ai.getAndIncrement(); j < nucleiToSplit.size(); j = ai
							.getAndIncrement()) {
						label = nucleiToSplit.get(j);

						targetNucleiNumber = estimateNucleiNumber(label);

						if (targetNucleiNumber > 1) {
							split(label, targetNucleiNumber);
						} else {
							spots.add(createSpotFomLabel(label));
						}
					}
				}

			};
		}
		SimpleMultiThreading.startAndJoin(threads);

		long end = System.currentTimeMillis();
		processingTime = end - start;
		return true;
	}

	/*
	 * PRIVATE METHODS
	 */

	/**
	 * Instantiate a new {@link Spot} from a blob in the {@link #source}
	 * labelling.
	 * 
	 * @param label
	 *            the blob label to operate on
	 * @return a new spot object, representing the labled blob
	 */
	private Spot createSpotFomLabel(Integer label) {
		double nucleusVol = source.getArea(label) * voxelVolume;
		float radius = (float) Math
				.pow(3 * nucleusVol / (4 * Math.PI), 0.33333);
		float[] coordinates = getCentroid(label);
		Spot spot = new SpotImp(coordinates);
		spot.putFeature(Spot.RADIUS, radius);
		return spot;
	}

	/**
	 * Estimate the number of nuclei likely to be found in a big blob.
	 * <p>
	 * Here, we apply Bhavna Rajasekaran method: We build the histograms of
	 * pixel position in X, Y and Z, and we assume the most likely number of
	 * nuclei is the sum of peaks in each of these 3 histograms.
	 * 
	 * @param label
	 *            the label of the blob to examine in the {@link #source} image
	 * @return the estimated number of nuclei to split it into
	 */
	private int estimateNucleiNumber(Integer label) {

		// Prepare histogram holders
		int[] minExtents = new int[source.getNumDimensions()];
		int[] maxExtents = new int[source.getNumDimensions()];
		source.getExtents(label, minExtents, maxExtents);
		
		int[][] histograms = new int[source.getNumDimensions()][];
		for (int i = 0; i < source.getNumDimensions(); i++) {
			histograms[i] = new int[maxExtents[i] - minExtents[i]];
		}
		
		// Build histograms
		LocalizableCursor<FakeType> cursor = source.createLocalizableLabelCursor(label);
		int[] position = source.createPositionArray();
		while(cursor.hasNext()) {
			cursor.fwd();
			cursor.getPosition(position);
			for (int i = 0; i < position.length; i++) {
				histograms[i] [ position[i] - minExtents[i] ] ++;
			}
		}
		cursor.close();
		
		// Investigate histograms
		int nPeaks = 0;
		for (int i = 0; i < histograms.length; i++) {
			int[] h = histograms[i];
			boolean wasGoingUp = true;
			
			int previousVal = h[0];
			for (int j = 1; j < h.length; j++) {
				if (h[j] <= previousVal && wasGoingUp) { // plateau protection
					// We are going down and were going up, so this is a peak
					nPeaks++;
					wasGoingUp = false;
				} else if (h[j] < previousVal) {
					wasGoingUp = false;
				} else {
					wasGoingUp = true;
				}
				previousVal = h[j];
			}
			// Check if last value is a peak as well
			if (wasGoingUp) {
				nPeaks++;
			}
		}
		return nPeaks;
	}

	/**
	 * Split the volume in the source image with the given label in the given
	 * number of nuclei. Splitting is made using K-means++ clustering using
	 * calibrated euclidean distance.
	 */
	private void split(Integer label, int n) {
		// Harvest pixel coordinates in a collection of calibrated clusterable
		// points
		int volume = (int) source.getArea(label);
		Collection<CalibratedEuclideanIntegerPoint> pixels = new ArrayList<CalibratedEuclideanIntegerPoint>(
				volume);
		LocalizableCursor<FakeType> cursor = source
				.createLocalizableLabelCursor(label);
		while (cursor.hasNext()) {
			cursor.fwd();
			int[] position = cursor.getPosition().clone();
			pixels.add(new CalibratedEuclideanIntegerPoint(position,
					calibration));
		}
		cursor.close();

		// Do K-means++ clustering
		KMeansPlusPlusClusterer<CalibratedEuclideanIntegerPoint> clusterer = new KMeansPlusPlusClusterer<CalibratedEuclideanIntegerPoint>(
				new Random());
		List<Cluster<CalibratedEuclideanIntegerPoint>> clusters = clusterer
				.cluster(pixels, n, -1);

		// Create spots from clusters
		for (Cluster<CalibratedEuclideanIntegerPoint> cluster : clusters) {
			float[] centroid = new float[calibration.length];
			int npoints = cluster.getPoints().size();
			for (CalibratedEuclideanIntegerPoint p : cluster.getPoints()) {
				for (int i = 0; i < centroid.length; i++) {
					centroid[i] += (p.getPoint()[i] * calibration[i]) / npoints;
				}
			}
			final double voxelVolume = calibration[0] * calibration[1]
					* calibration[2];
			double nucleusVol = cluster.getPoints().size() * voxelVolume;
			float radius = (float) Math.pow(3 * nucleusVol / (4 * Math.PI),
					0.33333);
			Spot spot = new SpotImp(centroid);
			spot.putFeature(Spot.RADIUS, radius);
			spot.putFeature(Spot.QUALITY, (float) 1 / n); // split spot get a
															// quality of 1 over
															// the number of
															// spots in the
															// initial cluster
			synchronized (spots) {
				spots.add(spot);
			}
		}
	}

	/**
	 * Get an estimate of the actual single nuclei volume, to use in subsequent
	 * steps, when splitting touching nuclei.
	 * <p>
	 * Executing this methods also sets the following fields:
	 * <ul>
	 * <li> {@link #thrashedLabels} the list of labels that should be erased from
	 * the source labeling. It contains the label of nuclei that are too big or
	 * too small to be enve considered for splitting.
	 * <li> {@link #nucleiToSplit} the list of labels for the nuclei that should
	 * be considered for splitting. They are between acceptable bounds, but have
	 * a volume too large compared to the computed estimate to be made of a
	 * single nucleus.
	 * </ul>
	 * 
	 * @return the best volume estimate, as a long primitive
	 */
	private long getVolumeEstimate() {

		ArrayList<Integer> labels = new ArrayList<Integer>(source.getLabels());

		// Discard nuclei too big or too small;
		thrashedLabels = new ArrayList<Integer>(labels.size() / 10);
		long volume;
		for (Integer label : labels) {
			volume = source.getArea(label);
			if (volume >= volumeThresholdUp || volume <= volumeThresholdBottom) {
				thrashedLabels.add(label);
			}
		}
		if (DEBUG) {
			System.out.println("[NucleiSplitter] Removing "
					+ thrashedLabels.size() + " bad nuclei out of "
					+ labels.size());
		}
		labels.removeAll(thrashedLabels);
		int nNuclei = labels.size();

		// Compute mean and std of volume distribution
		long sum = 0;
		long sum_sqr = 0;
		long v;
		for (Integer label : labels) {
			v = source.getArea(label);
			sum += v;
			sum_sqr += v * v;
		}
		long mean = sum / nNuclei;
		long std = (long) Math.sqrt((sum_sqr - sum * mean) / nNuclei);

		// Harvest suspicious nuclei
		nucleiToSplit = new ArrayList<Integer>(nNuclei / 5);
		long splitThreshold = (long) (mean + stdFactor * std);
		for (Integer label : labels) {
			volume = source.getArea(label);
			if (volume >= splitThreshold) {
				nucleiToSplit.add(label);
			}
		}

		// Build non-suspicious nuclei list
		final ArrayList<Integer> nonSuspiciousNuclei = new ArrayList<Integer>(
				labels);
		nonSuspiciousNuclei.removeAll(nucleiToSplit);
		if (DEBUG) {
			System.out.println("[NucleiSplitter] Found " + nucleiToSplit.size()
					+ " nuclei to split out of " + labels.size());
		}

		// Harvest non-suspicious nuclei as spots
		for (Integer label : nonSuspiciousNuclei) {
			Spot spot = createSpotFomLabel(label);
			spot.putFeature(Spot.QUALITY, 1); // non-suspicious spots get a
												// quality of 1
			spots.add(spot);
		}

		long volumeEstimate = mean;
		if (DEBUG) {
			System.out
					.println("[NucleiSplitter] Single nucleus volume estimate: "
							+ volumeEstimate + " voxels");
		}
		return volumeEstimate;
	}

	private float[] getCentroid(final Integer label) {
		final float[] centroid = new float[3];
		final int[] position = source.createPositionArray();
		LocalizableCursor<FakeType> cursor = source
				.createLocalizableLabelCursor(label);
		int npixels = 0;
		while (cursor.hasNext()) {
			cursor.fwd();
			cursor.getPosition(position);
			for (int i = 0; i < position.length; i++) {
				centroid[i] += position[i] * calibration[i];
			}
			npixels++;
		}
		for (int i = 0; i < centroid.length; i++) {
			centroid[i] /= npixels;
		}
		return centroid;
	}

}