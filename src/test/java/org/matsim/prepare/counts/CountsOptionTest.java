package org.matsim.prepare.counts;

import org.junit.Test;
import org.matsim.api.core.v01.network.Link;
import org.matsim.counts.Counts;
import org.matsim.counts.MatsimCountsReader;

import static org.junit.Assert.assertEquals;

public class CountsOptionTest {

    String ignored = "C:\\Users\\ACER\\Desktop\\Uni\\VSP\\Ruhrgebiet\\Testdaten\\ignored.csv";
    String manual = "C:\\Users\\ACER\\Desktop\\Uni\\VSP\\Ruhrgebiet\\Testdaten\\manual.csv";
    String mixed = "C:\\Users\\ACER\\Desktop\\Uni\\VSP\\Ruhrgebiet\\Testdaten\\manual-mixed-aggregation.csv";
    String countsFromBastFilepath = "C:\\Users\\ACER\\Desktop\\Uni\\VSP\\Ruhrgebiet\\counts-ruhr-2019.xml.gz";

    @Test
    public void testInitilaziationWithMixedAggregation(){
        CountsOption option = new CountsOption(ignored, mixed, ';');
        option.initialize();

        assertEquals(12, option.getManualMatchedCounts().getCounts().size());
    }

    @Test
    public void testMergeWithMaunalMatched(){

        Counts<Link> byScriptCounts = new Counts<>();
        new MatsimCountsReader(byScriptCounts).readFile(countsFromBastFilepath);

        int bySriptSize = byScriptCounts.getCounts().size();

        CountsOption option = new CountsOption(ignored, manual, ';');
        option.initialize().mergeWithManualMatched(byScriptCounts);

        int manualSize = option.getManualMatchedCounts().getCounts().size();
        int mergedSize = byScriptCounts.getCounts().size();

        assertEquals(mergedSize, manualSize + bySriptSize);
    }
}