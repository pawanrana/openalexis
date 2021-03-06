/*   Copyright 2012 Tim Garrett, Mothsoft LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.mothsoft.alexis.engine.numeric;

import java.sql.Timestamp;
import java.util.List;

import com.mothsoft.alexis.domain.DataSet;
import com.mothsoft.alexis.domain.TimeUnits;

public interface CorrelationCalculator {

    /**
     * Get the r-value for the data sets over the time range specified
     */
    public double correlate(DataSet ds1, DataSet ds2, Timestamp startDate, Timestamp endDate, TimeUnits granularity);

    /**
     * Get the correlation matrix for the data sets <br />
     * position [a][b] when a=b contains identity r-value, 1.0 <br />
     * position [0][1] contains the r-value of the first data set against the
     * second data set
     */
    public double[][] correlate(List<DataSet> dataSets, Timestamp startDate, Timestamp endDate,
            TimeUnits granularity);

}
