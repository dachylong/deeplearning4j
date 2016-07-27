package org.deeplearning4j.spark.datavec;

import org.apache.spark.api.java.function.Function;
import org.datavec.api.io.WritableConverter;
import org.datavec.api.io.converters.WritableConverterException;
import org.datavec.api.writable.Writable;
import org.datavec.common.data.NDArrayWritable;
import org.deeplearning4j.datasets.datavec.RecordReaderDataSetIterator;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.DataSetPreProcessor;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.util.FeatureUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**Map {@code Collection<Writable>} objects (out of a datavec-spark record reader function) to DataSet objects for Spark training.
 * Analogous to {@link RecordReaderDataSetIterator}, but in the context of Spark.
 * @author Alex Black
 */
public class DataVecDataSetFunction implements Function<List<Writable>,DataSet>, Serializable {

    private final int labelIndex;
    private final int labelIndexTo;
    private final int numPossibleLabels;
    private final boolean regression;
    private final DataSetPreProcessor preProcessor;
    private final WritableConverter converter;
    protected int batchSize = -1;

    public DataVecDataSetFunction(int labelIndex, int numPossibleLabels, boolean regression){
        this(labelIndex, numPossibleLabels, regression, null, null);
    }

    /**
     * @param labelIndex Index of the label column
     * @param numPossibleLabels Number of classes for classification  (not used if regression = true)
     * @param regression False for classification, true for regression
     * @param preProcessor DataSetPreprocessor (may be null)
     * @param converter WritableConverter (may be null)
     */
    public DataVecDataSetFunction(int labelIndex, int numPossibleLabels, boolean regression,
                                  DataSetPreProcessor preProcessor, WritableConverter converter) {
        this(labelIndex, labelIndex, numPossibleLabels, regression, preProcessor, converter);
    }

    public DataVecDataSetFunction(int labelIndexFrom, int labelIndexTo, int numPossibleLabels, boolean regression,
                                  DataSetPreProcessor preProcessor, WritableConverter converter){
        this.labelIndex = labelIndexFrom;
        this.labelIndexTo = labelIndexTo;
        this.numPossibleLabels = numPossibleLabels;
        this.regression = regression;
        this.preProcessor = preProcessor;
        this.converter = converter;
    }

    @Override
    public DataSet call(List<Writable> currList) throws Exception {

        //allow people to specify label index as -1 and infer the last possible label
        int labelIndex = this.labelIndex;
        if (numPossibleLabels >= 1 && labelIndex < 0) {
            labelIndex = currList.size() - 1;
        }

        INDArray label = null;
        INDArray featureVector = null;
        int featureCount = 0;

        //no labels
        if(currList.size() == 2 && currList.get(1) instanceof NDArrayWritable && currList.get(0) instanceof NDArrayWritable && currList.get(0) == currList.get(1)) {
            NDArrayWritable writable = (NDArrayWritable)currList.get(0);
            return new DataSet(writable.get(),writable.get());
        }
        if(currList.size() == 2 && currList.get(0) instanceof NDArrayWritable) {
            if(!regression)
                label = FeatureUtil.toOutcomeVector((int) Double.parseDouble(currList.get(1).toString()),numPossibleLabels);
            else
                label = Nd4j.scalar(Double.parseDouble(currList.get(1).toString()));
            NDArrayWritable ndArrayWritable = (NDArrayWritable) currList.get(0);
            featureVector = ndArrayWritable.get();
            return new DataSet(featureVector,label);
        }

        for (int j = 0; j < currList.size(); j++) {
            Writable current = currList.get(j);
            //ndarray writable is an insane slow down herecd
            if (!(current instanceof  NDArrayWritable) && current.toString().isEmpty())
                continue;

            if (labelIndex >= 0 && j == labelIndex) {
                //single label case (classification, etc)
                if (converter != null)
                    try {
                        current = converter.convert(current);
                    } catch (WritableConverterException e) {
                        e.printStackTrace();
                    }
                if (numPossibleLabels < 1)
                    throw new IllegalStateException("Number of possible labels invalid, must be >= 1");
                if (regression) {
                    label = Nd4j.scalar(current.toDouble());
                } else {
                    int curr = current.toInt();
                    if (curr >= numPossibleLabels)
                        curr--;
                    label = FeatureUtil.toOutcomeVector(curr, numPossibleLabels);
                }
            } else {
                try {
                    double value = current.toDouble();
                    if (featureVector == null) {
                        if(regression && labelIndex >= 0){
                            //Handle the possibly multi-label regression case here:
                            int nLabels = labelIndexTo - labelIndex + 1;
                            featureVector = Nd4j.create(1, currList.size() - nLabels);
                        } else {
                            //Classification case, and also no-labels case
                            featureVector = Nd4j.create(labelIndex >= 0 ? currList.size() - 1 : currList.size());
                        }
                    }
                    featureVector.putScalar(featureCount++, value);
                } catch (UnsupportedOperationException e) {
                    // This isn't a scalar, so check if we got an array already
                    if (current instanceof NDArrayWritable) {
                        assert featureVector == null;
                        featureVector = ((NDArrayWritable)current).get();
                    } else {
                        throw e;
                    }
                }
            }
        }

        DataSet ds = new DataSet(featureVector, (labelIndex >= 0 ? label : featureVector) );
        if(preProcessor != null) preProcessor.preProcess(ds);
        return ds;
    }
}
