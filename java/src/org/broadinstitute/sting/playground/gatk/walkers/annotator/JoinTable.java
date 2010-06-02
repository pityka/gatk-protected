/*
 * Copyright (c) 2010 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.playground.gatk.walkers.annotator;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import org.broadinstitute.sting.utils.StingException;

/**
 * This is a container that holds all data corresponding to a single join table as specified by one -J arg (ex: -J bindingName1,/path/to/file,bindingName1.columnName=bindingName2.columnName2).
 * Some terminology:
 * 'bindingName' is an arbitrary label for a given table that is specified on the command line with either the -B or -J arg.
 * In the example above, bindingName1 is the 'local' binding name because it is attached to the join table file provided with this -J arg. bindingName2 is the 'external' binding name because
 * it corresponds to some other table specified previously with another -B or -J arg.
 *
 * The JoinTable object stores a map entry for each record in the join table. The entry's key is the value of the join column in a given record (eg. bindingName1.columnName in the above example),
 * and the entry value is an ArrayList representing the entire join table record.
 * The JoinTable object also stores some other join table parameters such as the column names that were parsed out of the file header, and the bindingNames and columnNames from the -J arg.
 *
 * The join operation is performed by looking up the value of the join column in the external table (the one that this table is being joined to), and then using this value to do a lookup
 * on the map - if there's a hit, it will provide the record from the join table that is to be joined with the record in the external table.
 *
 * More information can be found here: http://www.broadinstitute.org/gsa/wiki/index.php/GenomicAnnotator
 */
public class JoinTable
{
    //the list of join table column names parsed out of the file header.
    private List<String> columnNames; //not fully-qualified

    private String localBindingName;
    private String externalBindingName;
    private String externalColumnName;

    //stores a map entry for each record in the join table. The entry's key is the value of the join column in a given record (eg. bindingName.columnName in the above example),
    //and the entry value is an ArrayList representing the entire join table record.
    private HashMap<String, List<ArrayList<String>>> joinColumnValueToRecords = new HashMap<String, List<ArrayList<String>>>();

    private boolean parsedFromFile = false;

    /**
     * Parses the table from the given file using the JoinTableParser.
     *
     * @param filename The file containing the table.
     * @param localColumnName The column name within the given file to join on.
     * @param externalBindingName The bindingName of another file (previously specified with either -B or -J).
     * @param externalColumnName The columnName in this other file to join on.
     * @param strict Whether to throw an exception if the number of columnNames in the header doesn't match the number of values in any row in the file specified by filename.
     */
    public void parseFromFile(String filename, String localBindingName, String localColumnName, String externalBindingName, String externalColumnName, boolean strict)  {
        if(parsedFromFile) {
            throw new StingException("parseFromFile(" + filename +", ..) called more than once");
        }
        parsedFromFile = true;

        setLocalBindingName(localBindingName);
        setExternalBindingName(externalBindingName);
        setExternalColumnName(externalColumnName);

        BufferedReader br = null;
        try
        {
            br = new BufferedReader(new FileReader(filename));
            final JoinTableParser parser = new JoinTableParser(strict);

            //read in the header
            final List<String> header = parser.readHeader(br);
            columnNames = header;

            //get the index of the localJoinColumnName
            int localColumnNameIdx = -1;
            for(int i = 0; i < header.size(); i++) {
                final String columnName = header.get(i);
                if(columnName.equals(localColumnName)) {
                    localColumnNameIdx = i;
                    break;
                }
            }

            if(localColumnNameIdx == -1) {
                throw new StingException("The -J arg specifies an unknown column name: \"" + localColumnName + "\". It's not one of the column names in the header " + columnNames + " of the file: " + filename);
            }


            //read in all records and create a map entry for each
            String line = null;
            while((line = br.readLine()) != null) {
                final ArrayList<String> columnValues = parser.parseLine(line);
                final String joinColumnValue = columnValues.get(localColumnNameIdx);
                put(joinColumnValue, columnValues);
            }
        }
        catch(IOException e)
        {
            throw new StingException("Unable to parse file: " + filename, e);
        }
        finally
        {
            try {
                if(br != null) {
                    br.close();
                }
            } catch(IOException e) {
                throw new StingException("Unable to close file: " + filename, e);
            }
        }
    }



    /**
     * If the -J arg was:  -J bindingName1,/path/to/file,bindingName1.columnName=bindingName2.columnName2,
     * this returns bindingName1.
     * @return
     */
    public String getLocalBindingName() {
        return localBindingName;
    }

    public void setLocalBindingName(String localBindingName) {
        this.localBindingName = localBindingName;
    }


    /**
     * @return the list of join table column names parsed out of the file header.
     */
    public List<String> getColumnNames() {
        return columnNames; //not fully-qualified
    }

    protected void setColumnNames(List<String> columnNames) {
        this.columnNames = columnNames;
    }

    /**
     * If the -J arg was:  -J bindingName1,/path/to/file,bindingName1.columnName=bindingName2.columnName2,
     * this returns columnName2.
     * @return
     */
    public String getExternalColumnName() {
        return externalColumnName;
    }

    protected void setExternalColumnName(
            String externalColumnName) {
        this.externalColumnName = externalColumnName;
    }


    /**
     * If the -J arg was:  -J bindingName1,/path/to/file,bindingName1.columnName=bindingName2.columnName2,
     * this returns bindingName2.
     * @return
     */
    public String getExternalBindingName() {
        return externalBindingName;
    }

    protected void setExternalBindingName(
            String externalBindingName) {
        this.externalBindingName = externalBindingName;
    }

    /**
     * Whether any join table records have the given value in the join column.
     * @param value
     * @return
     */
    public boolean containsJoinColumnValue(String joinColumnValue) {
        return joinColumnValueToRecords.containsKey(joinColumnValue);
    }

    /**
     * Returns all records in the table where the join column has the given value.
     * @param joinColumnValue
     * @return
     */
    public List<ArrayList<String>> get(String joinColumnValue) {
        return joinColumnValueToRecords.get(joinColumnValue);
    }

    /**
     * Adds the given record to the map.
     * @param joinColumnValue
     * @param record
     */
    protected void put(String joinColumnValue, ArrayList<String> record) {
        List<ArrayList<String>> list = joinColumnValueToRecords.get(joinColumnValue);
        if(list == null) {
            list = new LinkedList<ArrayList<String>>();
            joinColumnValueToRecords.put(joinColumnValue, list);
        }
        list.add(record);
    }
}