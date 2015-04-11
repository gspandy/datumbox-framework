/**
 * Copyright (C) 2013-2015 Vasilis Vryniotis <bbriniotis@datumbox.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datumbox.framework.machinelearning.common.bases.featureselection;

import com.datumbox.common.dataobjects.Dataset;
import com.datumbox.common.dataobjects.Dataset.ColumnType;
import com.datumbox.common.dataobjects.Record;
import com.datumbox.common.persistentstorage.interfaces.DatabaseConnector;
import com.datumbox.common.persistentstorage.interfaces.BigMap;
import com.datumbox.common.persistentstorage.interfaces.DatabaseConfiguration;
import com.datumbox.common.utilities.TypeConversions;


import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;



/**
 * Abstract class which is the base of every Categorical Feature Selection algorithm.
 * 
 * @author Vasilis Vryniotis <bbriniotis@datumbox.com>
 * @param <MP>
 * @param <TP>
 */
public abstract class CategoricalFeatureSelection<MP extends CategoricalFeatureSelection.ModelParameters, TP extends CategoricalFeatureSelection.TrainingParameters> extends FeatureSelection<MP, TP> {
    
    
    public static abstract class ModelParameters extends FeatureSelection.ModelParameters {

        private int N;

        @BigMap
        
        private Map<Object, Double> featureScores; //map which stores the scores of the features

        public ModelParameters(DatabaseConnector dbc) {
            super(dbc);
        }
        
        //Getters and Setters
        
        public int getN() {
            return N;
        }

        public void setN(int N) {
            this.N = N;
        }
        public Map<Object, Double> getFeatureScores() {
            return featureScores;
        }

        public void setFeatureScores(Map<Object, Double> featureScores) {
            this.featureScores = featureScores;
        }
        
    }
    
    
    public static abstract class TrainingParameters extends FeatureSelection.TrainingParameters {
        
        private Integer rareFeatureThreshold = null;
        private Integer maxFeatures=null;
        private boolean ignoringNumericalFeatures = true;

        public Integer getRareFeatureThreshold() {
            return rareFeatureThreshold;
        }

        public void setRareFeatureThreshold(Integer rareFeatureThreshold) {
            this.rareFeatureThreshold = rareFeatureThreshold;
        }

        public Integer getMaxFeatures() {
            return maxFeatures;
        }

        public void setMaxFeatures(Integer maxFeatures) {
            this.maxFeatures = maxFeatures;
        }

        public boolean isIgnoringNumericalFeatures() {
            return ignoringNumericalFeatures;
        }

        public void setIgnoringNumericalFeatures(boolean ignoringNumericalFeatures) {
            this.ignoringNumericalFeatures = ignoringNumericalFeatures;
        }
        
    }
    

    protected CategoricalFeatureSelection(String dbName, DatabaseConfiguration dbConf, Class<MP> mpClass, Class<TP> tpClass) {
        super(dbName, dbConf, mpClass, tpClass);
    }
    
    
    @Override
    protected void _fit(Dataset data) {
        
        //set the number of observations
        MP modelParameters = knowledgeBase.getModelParameters();
        
        modelParameters.setN(data.size());
        
        
        DatabaseConnector dbc = knowledgeBase.getDbc();
        
        Map<Object, Integer> tmp_classCounts = dbc.getBigMap("tmp_classCounts", true); //map which stores the counts of the classes
        Map<List<Object>, Integer> tmp_featureClassCounts = dbc.getBigMap("tmp_featureClassCounts", true); //map which stores the counts of feature-class combinations.
        Map<Object, Double> tmp_featureCounts = dbc.getBigMap("tmp_featureCounts", true); //map which stores the counts of the features

        
        //build the maps with teh feature statistics and counts
        buildFeatureStatistics(data, tmp_classCounts, tmp_featureClassCounts, tmp_featureCounts);
        
        
        
        
        //call the overriden method to get the scores of the features.
        //WARNING: do not use feature scores for any weighting. Sometimes the features are selected based on a minimum and others on a maximum criterion.
        estimateFeatureScores(tmp_classCounts, tmp_featureClassCounts, tmp_featureCounts);
        

        //drop the unnecessary stastistics tables
        dbc.dropBigMap("tmp_classCounts", tmp_classCounts);
        dbc.dropBigMap("tmp_featureClassCounts", tmp_featureClassCounts);
        dbc.dropBigMap("tmp_featureCounts", tmp_featureCounts);
    }
    
    @Override
    protected void filterFeatures(Dataset newdata) {
        //now filter the data by removing all the features that are not selected
        filterData(newdata, knowledgeBase.getDbc(), knowledgeBase.getModelParameters().getFeatureScores(), knowledgeBase.getTrainingParameters().isIgnoringNumericalFeatures());
    }
    
    private static void filterData(Dataset data, DatabaseConnector dbc, Map<Object, Double> featureScores, boolean ignoringNumericalFeatures) {
        
        Map<Object, Boolean> tmp_removedColumns = dbc.getBigMap("tmp_removedColumns", true);
        
        for(Map.Entry<Object, ColumnType> entry: data.getColumns().entrySet()) {
            Object feature = entry.getKey();
            
            if(ignoringNumericalFeatures) {
                if(entry.getValue()==Dataset.ColumnType.NUMERICAL) { //is it numerical? 
                    continue; //skip any further analysis
                }
            }
            
            if(!featureScores.containsKey(feature)) {
                tmp_removedColumns.put(feature, true);
            }
        }
        
        
        data.removeColumns(tmp_removedColumns.keySet());
        
        //Drop the temporary Collection
        dbc.dropBigMap("tmp_removedColumns", tmp_removedColumns);
        
        
        
    }
    
    public static void removeRareFeatures(Dataset data, DatabaseConnector dbc, Integer rareFeatureThreshold, Map<Object, Double> featureCounts, boolean ignoringNumericalFeatures) {
        //This method contains part of the statistics collection of the object
        //but can also be called statically in order to aggressively remove rare
        //features especially in NLP applications. It was developed in such a way
        //so that duplicate code is reduced. To do so, "call by reference" is 
        //required. In Java what we can do is initialize an empty featureCounts
        //map externally and pass it here to be constructred. When called by the
        //object itself this map is part of the statistics that it collects during
        //feature selection. If called statically, the map should be instatiated
        //just before the call to this method and dropped immediately after 
        //since it has no use.
        
        if(!featureCounts.isEmpty()) {
            throw new RuntimeException("The featureCounts map should be empty.");
        }
        
        Map<Object, Dataset.ColumnType> columnTypes = data.getColumns();
        
        //find the featureCounts
        for(Integer rId : data) {
            Record r = data.get(rId);
            for(Map.Entry<Object, Object> entry : r.getX().entrySet()) {
                Object feature = entry.getKey();
                
                if(ignoringNumericalFeatures) { //if we ignore the numerical features, investigate further if we must skip the feature
                    if(columnTypes.get(feature)==Dataset.ColumnType.NUMERICAL) { //is it numerical? 
                        continue; //skip any further analysis
                    }
                }
                
                Double value = TypeConversions.toDouble(entry.getValue());
                if(value==null || value==0.0) {
                    continue;
                }


                
                //feature counts
                Double featureCounter = featureCounts.get(feature);
                if(featureCounter==null) {
                    featureCounter=0.0;
                }
                featureCounts.put(feature, ++featureCounter);
                
            }
        }

        //remove rare features
        if(rareFeatureThreshold != null && rareFeatureThreshold>0) {
            //remove features from the featureCounts list
            Iterator<Map.Entry<Object, Double>> it = featureCounts.entrySet().iterator();
            while(it.hasNext()) {
                Map.Entry<Object, Double> entry = it.next();
                if(entry.getValue()<=rareFeatureThreshold) {
                    it.remove(); 
                }
            }
            
            //then remove the features in dataset that does not appear in the list
            filterData(data, dbc, featureCounts, ignoringNumericalFeatures);
        }
    }
    
    private void buildFeatureStatistics(Dataset data, Map<Object, Integer> classCounts, Map<List<Object>, Integer> featureClassCounts, Map<Object, Double> featureCounts) {        
        TP trainingParameters = knowledgeBase.getTrainingParameters();
        Integer rareFeatureThreshold = trainingParameters.getRareFeatureThreshold();
        boolean ignoringNumericalFeatures = trainingParameters.isIgnoringNumericalFeatures();
        
        //the method below does not only removes the rare features but also
        //first and formost calculates the contents of featureCounts map. 
        //The map must be empty or else you get a RuntimeException
        removeRareFeatures(data, knowledgeBase.getDbc(), rareFeatureThreshold, featureCounts, ignoringNumericalFeatures);
        
        //now find the classCounts and the featureClassCounts
        for(Integer rId : data) {
            Record r = data.get(rId);
            Object theClass = r.getY();

            //class counts
            Integer classCounter = classCounts.get(theClass);
            if(classCounter==null) {
                classCounter=0;
            }
            classCounts.put(theClass, ++classCounter);


            for(Map.Entry<Object, Object> entry : r.getX().entrySet()) {
                Object feature = entry.getKey();
                
                if(ignoringNumericalFeatures) { //if we ignore the numerical features, investigate further if we must skip the feature
                    if(data.getColumns().get(feature)==Dataset.ColumnType.NUMERICAL) { //is it numerical? 
                        continue; //skip any further analysis
                    }
                }
                
                Double value = TypeConversions.toDouble(entry.getValue());
                if(value==null || value==0.0) {
                    continue;
                }



                //featureClass counts
                List<Object> featureClassTuple = Arrays.<Object>asList(feature, theClass);
                Integer featureClassCounter = featureClassCounts.get(featureClassTuple);
                if(featureClassCounter==null) {
                    featureClassCounter=0;
                }
                featureClassCounts.put(featureClassTuple, ++featureClassCounter);
            }


        }
        
    }
    
    protected abstract void estimateFeatureScores(Map<Object, Integer> classCounts, Map<List<Object>, Integer> featureClassCounts, Map<Object, Double> featureCounts);
}
