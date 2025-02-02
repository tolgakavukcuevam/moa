/**
 * Subspace MOA [DenStream_DBSCAN.java]
 * 
 * DenStream with DBSCAN as the macro-clusterer.
 * 
 * @author Stephan Wels (stephan.wels@rwth-aachen.de)
 * @editor Yunsu Kim
 * Data Management and Data Exploration Group, RWTH Aachen University
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *    
 *    
 */

package moa.clusterers.denstream;

import com.yahoo.labs.samoa.instances.Attribute;
import com.yahoo.labs.samoa.instances.Instances;
import com.yahoo.labs.samoa.instances.InstancesHeader;
import java.util.ArrayList;

import moa.cluster.Cluster;
import moa.cluster.Clustering;
import moa.clusterers.AbstractClusterer;
import moa.clusterers.macro.dbscan.DBScan;
import moa.core.Measurement;
import com.yahoo.labs.samoa.instances.DenseInstance;
import com.yahoo.labs.samoa.instances.Instance;

public class WithDBSCAN extends AbstractClusterer {
	
	private static final long serialVersionUID = 1L;

	private double weightThreshold = 0.01;
    private double lambda;
    private double epsilon;
    private int minPoints;
    private double mu;
    private double beta;
    private int initPoints;
    private double offlineOption;

    private Clustering p_micro_cluster;
    private Clustering o_micro_cluster;
    private ArrayList<DenPoint> initBuffer;

    private boolean initialized;
	private long timestamp = 0;
    private Timestamp currentTimestamp;
    private long tp;
	
	/* #point variables */
	protected int numInitPoints;
	protected int numProcessedPerUnit;
	protected int processingSpeed;

	// TODO Some variables to prevent duplicated processes
	private class DenPoint extends DenseInstance {
		
		private static final long serialVersionUID = 1L;
		
		protected boolean covered;

		public DenPoint(Instance nextInstance, Long timestamp) {
			super(nextInstance);
			this.setDataset(nextInstance.dataset());
		}
	}

	@Override
	public void resetLearningImpl() {
		// init DenStream
		currentTimestamp = new Timestamp();
//		lambda = -Math.log(weightThreshold) / Math.log(2)
//						/ (double) horizonOption.getValue();
		lambda = 0.25;

		epsilon = 0.02;
		minPoints = 1;// minPointsOption.getValue();
		mu = 1;
		beta = 0.2;
        initPoints = 1000;
        offlineOption = 2;

		initialized = false;
		p_micro_cluster = new Clustering();
		o_micro_cluster = new Clustering();
		initBuffer = new ArrayList<DenPoint>();
		
		tp = Math.round(1 / lambda * Math.log((beta * mu) / (beta * mu - 1))) + 1;
		
		numProcessedPerUnit = 0;
		processingSpeed = 100;
	}

	public void initialDBScan() {
		for (int p = 0; p < initBuffer.size(); p++) {
			DenPoint point = initBuffer.get(p);
			if (!point.covered) {
				point.covered = true;
				ArrayList<Integer> neighbourhood = getNeighbourhoodIDs(point,
						initBuffer, epsilon);
				if (neighbourhood.size() > minPoints) {
					MicroCluster mc = new MicroCluster(point,
							point.numAttributes(), timestamp, lambda,
							currentTimestamp);
					expandCluster(mc, initBuffer, neighbourhood);
					p_micro_cluster.add(mc);
				} else {
					point.covered = false;
				}
			}
		}
	}

	public void addNewAttributeIndexWithDefault(int newAttrIndex,double defaultValue,String paramName){
		/**
		 *
		 * Update init buffer with default value **
		 *
		 */
		ArrayList<DenPoint> newInitBuffer = new ArrayList<DenPoint>();
		double [] newDoubleArray;
		double [] tmpArray;
		Instance inst;
		InstancesHeader streamHeader;
		ArrayList<Attribute> attributes;
		for (int p = 0; p < initBuffer.size(); p++) {
			attributes = new ArrayList<Attribute>();
			DenPoint point = initBuffer.get(p);
			tmpArray = point.toDoubleArray();
			newDoubleArray = new double[tmpArray.length + 1];
			int j;
			for(j = 0 ; j< tmpArray.length ; j ++){
				attributes.add(point.attribute(j));
				newDoubleArray[j] = tmpArray[j];
			}
			attributes.add(new Attribute(paramName));
			newDoubleArray[j] = defaultValue;
			streamHeader = new InstancesHeader(new Instances("Evam Clustering Instance", attributes, tmpArray.length + 1));

			inst = new DenseInstance(1.0, newDoubleArray);
			inst.setDataset(streamHeader);
			newInitBuffer.add(new DenPoint(inst,currentTimestamp.getTimestamp()));
		}
		initBuffer = newInitBuffer;

		/***
		 *
		 * Update clusters
		 *
		 */
		p_micro_cluster = addNewDimensionToEnd(p_micro_cluster,defaultValue);
		o_micro_cluster = addNewDimensionToEnd(o_micro_cluster,defaultValue);
	}

	public void dropIndice(int index){
		ArrayList<DenPoint> newInitBuffer = new ArrayList<DenPoint>();
		double [] newDoubleArray;
		double [] tmpArray;
		Instance inst;
		InstancesHeader streamHeader;
		ArrayList<Attribute> attributes;

		for (int p = 0; p < initBuffer.size(); p++) {
			int offset = 0;
			attributes = new ArrayList<Attribute>();
			DenPoint point = initBuffer.get(p);
			tmpArray = point.toDoubleArray();
			newDoubleArray = new double[tmpArray.length - 1];
			for(int j = 0 ; j< tmpArray.length ; j ++){
				if(j == index){
					offset = -1;
					continue;
				}
				attributes.add(point.attribute(j));
				newDoubleArray[j + offset] = tmpArray[j];
			}
			streamHeader = new InstancesHeader(new Instances("Evam Clustering Instance", attributes, tmpArray.length - 1));
			inst = new DenseInstance(1.0, newDoubleArray);
			inst.setDataset(streamHeader);
			newInitBuffer.add(new DenPoint(inst,currentTimestamp.getTimestamp()));
		}
		initBuffer = newInitBuffer;
		p_micro_cluster = dropIndiceFromClustering(p_micro_cluster,index);
		o_micro_cluster = dropIndiceFromClustering(o_micro_cluster,index);
	}

	private Clustering dropIndiceFromClustering(Clustering micro_cluster, int index) {
		Clustering newMicroClustering = new Clustering();
		MicroCluster cluster;
		double[] oldCenter;
		double[] newCenter;
		MicroCluster microCluster;
		for(int i = 0 ; i < micro_cluster.size();i++){
			cluster = (MicroCluster) micro_cluster.get(i);
			oldCenter = cluster.getCenter();
			newCenter = new double[oldCenter.length - 1];
			int offset = 0;
			for(int j = 0 ; j < oldCenter.length ; j++){
				if(j == index){
					offset = -1;
					continue;
				}
				newCenter[j + offset] = oldCenter[j];
			}
			microCluster = new MicroCluster(newCenter,newCenter.length,cluster.getCreationTime(),
					cluster.getLambda(),
					cluster.getCurrentTimestamp());
			newMicroClustering.add(microCluster);
		}
		return newMicroClustering;
	}

	private Clustering addNewDimensionToEnd(Clustering micro_cluster, double defaultValue) {
		Clustering newMicroClustering = new Clustering();
		MicroCluster cluster;
		double[] oldCenter;
		double[] newCenter;
		MicroCluster microCluster;
		for(int i = 0 ; i < micro_cluster.size();i++){
			cluster = (MicroCluster) micro_cluster.get(i);
			oldCenter = cluster.getCenter();
			newCenter = new double[oldCenter.length + 1];
			 int j;
			 for(j = 0 ; j < oldCenter.length ; j++){
				 newCenter[j] = oldCenter[j];
			 }
			newCenter[j] = defaultValue;
			microCluster = new MicroCluster(newCenter,newCenter.length,cluster.getCreationTime(),
					cluster.getLambda(),
					cluster.getCurrentTimestamp());
			newMicroClustering.add(microCluster);
		}
		return newMicroClustering;
	}

	@Override
	public void trainOnInstanceImpl(Instance inst) {
		DenPoint point = new DenPoint(inst, timestamp);
		numProcessedPerUnit++;
		
		/* Controlling the stream speed */
		if (numProcessedPerUnit % processingSpeed == 0) {
			timestamp++;
			currentTimestamp.setTimestamp(timestamp);
		}		
		
		// ////////////////
		// Initialization//
		// ////////////////
		if (!initialized) {
			initBuffer.add(point);
            numInitPoints++;
			if (initBuffer.size() >= initPoints) {
				initialDBScan();
				initialized = true;
			}
		} else {
			// ////////////
			// Merging(p)//
			// ////////////
			boolean merged = false;
			if (p_micro_cluster.getClustering().size() != 0) {
				MicroCluster x = nearestCluster(point, p_micro_cluster);
				MicroCluster xCopy = x.copy();
				xCopy.insert(point, timestamp);
				if (xCopy.getRadius(timestamp) <= epsilon) {
					x.insert(point, timestamp);
					merged = true;
				}
			}
			if (!merged && (o_micro_cluster.getClustering().size() != 0)) {
				MicroCluster x = nearestCluster(point, o_micro_cluster);
				MicroCluster xCopy = x.copy();
				xCopy.insert(point, timestamp);

				if (xCopy.getRadius(timestamp) <= epsilon) {
					x.insert(point, timestamp);
					merged = true;
					if (x.getWeight() > beta * mu) {
						o_micro_cluster.getClustering().remove(x);
						p_micro_cluster.getClustering().add(x);
					}
				}
			}
			if (!merged) {
				o_micro_cluster.getClustering().add(
						new MicroCluster(point.toDoubleArray(), point
								.toDoubleArray().length, timestamp, lambda,
								currentTimestamp));
			}

			// //////////////////////////
			// Periodic cluster removal//
			// //////////////////////////
			if (timestamp % tp == 0) {
				ArrayList<MicroCluster> removalList = new ArrayList<MicroCluster>();
				for (Cluster c : p_micro_cluster.getClustering()) {
					if (((MicroCluster) c).getWeight() < beta * mu) {
						removalList.add((MicroCluster) c);
					}
				}
				for (Cluster c : removalList) {
					p_micro_cluster.getClustering().remove(c);
				}

				for (Cluster c : o_micro_cluster.getClustering()) {
					long t0 = ((MicroCluster) c).getCreationTime();
					double xsi1 = Math
							.pow(2, (-lambda * (timestamp - t0 + tp))) - 1;
					double xsi2 = Math.pow(2, -lambda * tp) - 1;
					double xsi = xsi1 / xsi2;
					if (((MicroCluster) c).getWeight() < xsi) {
						removalList.add((MicroCluster) c);
					}
				}
				for (Cluster c : removalList) {
					o_micro_cluster.getClustering().remove(c);
				}
			}

		}
	}

	private void expandCluster(MicroCluster mc, ArrayList<DenPoint> points,
			ArrayList<Integer> neighbourhood) {
		for (int p : neighbourhood) {
			DenPoint npoint = points.get(p);
			if (!npoint.covered) {
				npoint.covered = true;
				mc.insert(npoint, timestamp);
				ArrayList<Integer> neighbourhood2 = getNeighbourhoodIDs(npoint,
						initBuffer, epsilon);
				if (neighbourhood.size() > minPoints) {
					expandCluster(mc, points, neighbourhood2);
				}
			}
		}
	}

	private ArrayList<Integer> getNeighbourhoodIDs(DenPoint point,
			ArrayList<DenPoint> points, double eps) {
		ArrayList<Integer> neighbourIDs = new ArrayList<Integer>();
		for (int p = 0; p < points.size(); p++) {
			DenPoint npoint = points.get(p);
			if (!npoint.covered) {
				double dist = distance(point.toDoubleArray(), points.get(p)
						.toDoubleArray());
				if (dist < eps) {
					neighbourIDs.add(p);
				}
			}
		}
		return neighbourIDs;
	}

	private MicroCluster nearestCluster(DenPoint p, Clustering cl) {
		MicroCluster min = null;
		double minDist = 0;
		for (int c = 0; c < cl.size(); c++) {
			MicroCluster x = (MicroCluster) cl.get(c);
			if (min == null) {
				min = x;
			}
			double dist = distance(p.toDoubleArray(), x.getCenter());
			dist -= x.getRadius(timestamp);
			if (dist < minDist) {
				minDist = dist;
				min = x;
			}
		}
		return min;

	}

	private double distance(double[] pointA, double[] pointB) {
		double distance = 0.0;
		for (int i = 0; i < pointA.length; i++) {
			double d = pointA[i] - pointB[i];
			distance += d * d;
		}
		return Math.sqrt(distance);
	}

	public Clustering getClusteringResult() {
		DBScan dbscan = new DBScan(p_micro_cluster,offlineOption * epsilon, minPoints);
		return dbscan.getClustering(p_micro_cluster);
	}

	@Override
	public boolean implementsMicroClusterer() {
		return true;
	}

	@Override
	public Clustering getMicroClusteringResult() {
		return p_micro_cluster;
	}

	public Clustering getOutlierClusteringResult() {
		return o_micro_cluster;
	}

	@Override
	protected Measurement[] getModelMeasurementsImpl() {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public void getModelDescription(StringBuilder out, int indent) {
	}

	public boolean isRandomizable() {
		return true;
	}

	public double[] getVotesForInstance(Instance inst) {
		return null;
	}

	public boolean isInitialized() {
		return initialized;
	}

	@Override
	public String toString() {
		return "WithDBSCAN{" +
				"initialized=" + initialized +
				", timestamp=" + timestamp +
				",  clusters=" + getClusteringResult() +
				'}';
	}

	public double getWeightThreshold() {
		return weightThreshold;
	}

	public void setWeightThreshold(double weightThreshold) {
		this.weightThreshold = weightThreshold;
	}

	public double getLambda() {
		return lambda;
	}

	public void setLambda(double lambda) {
		this.lambda = lambda;
		this.tp = Math.round(1 / lambda * Math.log((beta * mu) / (beta * mu - 1))) + 1;
	}

	public double getEpsilon() {
		return epsilon;
	}

	public void setEpsilon(double epsilon) {
		this.epsilon = epsilon;
	}

	public int getMinPoints() {
		return minPoints;
	}

	public void setMinPoints(int minPoints) {
		this.minPoints = minPoints;
	}

	public double getMu() {
		return mu;
	}

	public void setMu(double mu) {
		this.mu = mu;
		this.tp = Math.round(1 / lambda * Math.log((beta * mu) / (beta * mu - 1))) + 1;
	}

	public double getBeta() {
		return beta;
	}

	public void setBeta(double beta) {
		this.beta = beta;
		this.tp = Math.round(1 / lambda * Math.log((beta * mu) / (beta * mu - 1))) + 1;
	}

	public ArrayList<DenPoint> getInitBuffer() {
		return initBuffer;
	}

	public void setInitBuffer(ArrayList<DenPoint> initBuffer) {
		this.initBuffer = initBuffer;
	}

	public void setInitialized(boolean initialized) {
		this.initialized = initialized;
	}

	public long getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(long timestamp) {
		this.timestamp = timestamp;
	}

	public Timestamp getCurrentTimestamp() {
		return currentTimestamp;
	}

	public void setCurrentTimestamp(Timestamp currentTimestamp) {
		this.currentTimestamp = currentTimestamp;
	}

	public long getTp() {
		return tp;
	}

	public void setTp(long tp) {
		this.tp = tp;
	}

	public int getNumInitPoints() {
		return numInitPoints;
	}

	public void setNumInitPoints(int numInitPoints) {
		this.numInitPoints = numInitPoints;
	}

    public int getInitPoints() {
        return initPoints;
    }

    public void setInitPoints(int initPoints) {
        this.initPoints = initPoints;
    }

    public double getOfflineOption() {
        return offlineOption;
    }

    public void setOfflineOption(double offlineOption) {
        this.offlineOption = offlineOption;
    }

    public int getNumProcessedPerUnit() {
		return numProcessedPerUnit;
	}

	public void setNumProcessedPerUnit(int numProcessedPerUnit) {
		this.numProcessedPerUnit = numProcessedPerUnit;
	}

	public int getProcessingSpeed() {
		return processingSpeed;
	}

	public void setProcessingSpeed(int processingSpeed) {
		this.processingSpeed = processingSpeed;
	}


}
