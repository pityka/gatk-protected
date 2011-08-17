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

package org.broadinstitute.sting.gatk.walkers;

import net.sf.picard.reference.ReferenceSequence;
import net.sf.picard.reference.ReferenceSequenceFile;
import net.sf.picard.reference.ReferenceSequenceFileFactory;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.util.StringUtil;
import org.broadinstitute.sting.commandline.Argument;
import org.broadinstitute.sting.commandline.Output;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.io.StingSAMFileWriter;
import org.broadinstitute.sting.gatk.refdata.ReadMetaDataTracker;
import org.broadinstitute.sting.utils.BaseUtils;
import org.broadinstitute.sting.utils.Utils;
import org.broadinstitute.sting.utils.clipreads.ClippingOp;
import org.broadinstitute.sting.utils.clipreads.ClippingRepresentation;
import org.broadinstitute.sting.utils.clipreads.ReadClipper;
import org.broadinstitute.sting.utils.collections.Pair;
import org.broadinstitute.sting.utils.sam.ReadUtils;

import java.io.File;
import java.io.PrintStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This ReadWalker provides simple, yet powerful read clipping capabilities.  It allows the user to clip bases in reads
 * with poor quality scores, that match particular sequences, or that were generated by particular machine cycles.
 */
@Requires({DataSource.READS})
public class ClipReadsWalker extends ReadWalker<ReadClipper, ClipReadsWalker.ClippingData> {
    @Output
    PrintStream out;

    /**
     * an optional argument to dump the reads out to a BAM file
     */
    @Argument(fullName = "outputBam", shortName = "ob", doc = "Write output to this BAM filename instead of STDOUT", required = false)
    StingSAMFileWriter outputBam = null;

    @Argument(fullName = "qTrimmingThreshold", shortName = "QT", doc = "", required = false)
    int qTrimmingThreshold = -1;

    @Argument(fullName = "cyclesToTrim", shortName = "CT", doc = "String of the form 1-10,20-30 indicating machine cycles to clip from the reads", required = false)
    String cyclesToClipArg = null;

    @Argument(fullName = "clipSequencesFile", shortName = "XF", doc = "Remove sequences within reads matching these sequences", required = false)
    String clipSequenceFile = null;

    @Argument(fullName = "clipSequence", shortName = "X", doc = "Remove sequences within reads matching this sequence", required = false)
    String[] clipSequencesArgs = null;

    @Argument(fullName="read", doc="", required=false)
    String onlyDoRead = null;

    //@Argument(fullName = "keepCompletelyClipped", shortName = "KCC", doc = "Unfortunately, sometimes a read is completely clipped away but with SOFTCLIP_BASES this results in an invalid CIGAR string.  ", required = false)
    //boolean keepCompletelyClippedReads = false;

//    @Argument(fullName = "onlyClipFirstSeqMatch", shortName = "ESC", doc="Only clip the first occurrence of a clipping sequence, rather than all subsequences within a read that match", required = false)
//    boolean onlyClipFirstSeqMatch = false;

    @Argument(fullName = "clipRepresentation", shortName = "CR", doc = "How should we actually clip the bases?", required = false)
    ClippingRepresentation clippingRepresentation = ClippingRepresentation.WRITE_NS;


    /**
     * List of sequence that should be clipped from the reads
     */
    List<SeqToClip> sequencesToClip = new ArrayList<SeqToClip>();

    /**
     * List of cycle start / stop pairs (0-based, stop is included in the cycle to remove) to clip from the reads
     */
    List<Pair<Integer, Integer>> cyclesToClip = null;

    /**
     * The initialize function.
     */
    public void initialize() {
        if (qTrimmingThreshold >= 0) {
            logger.info(String.format("Creating Q-score clipper with threshold %d", qTrimmingThreshold));
        }

        //
        // Initialize the sequences to clip
        //
        if (clipSequencesArgs != null) {
            int i = 0;
            for (String toClip : clipSequencesArgs) {
                i++;
                ReferenceSequence rs = new ReferenceSequence("CMDLINE-" + i, -1, StringUtil.stringToBytes(toClip));
                addSeqToClip(rs.getName(), rs.getBases());
            }
        }

        if (clipSequenceFile != null) {
            ReferenceSequenceFile rsf = ReferenceSequenceFileFactory.getReferenceSequenceFile(new File(clipSequenceFile));

            while (true) {
                ReferenceSequence rs = rsf.nextSequence();
                if (rs == null)
                    break;
                else {
                    addSeqToClip(rs.getName(), rs.getBases());
                }
            }
        }


        //
        // Initialize the cycle ranges to clip
        //
        if (cyclesToClipArg != null) {
            cyclesToClip = new ArrayList<Pair<Integer, Integer>>();
            for (String range : cyclesToClipArg.split(",")) {
                try {
                    String[] elts = range.split("-");
                    int start = Integer.parseInt(elts[0]) - 1;
                    int stop = Integer.parseInt(elts[1]) - 1;

                    if (start < 0) throw new Exception();
                    if (stop < start) throw new Exception();

                    logger.info(String.format("Creating cycle clipper %d-%d", start, stop));
                    cyclesToClip.add(new Pair<Integer, Integer>(start, stop));
                } catch (Exception e) {
                    throw new RuntimeException("Badly formatted cyclesToClip argument: " + cyclesToClipArg);
                }
            }
        }

        if (outputBam != null) {
            EnumSet<ClippingRepresentation> presorted = EnumSet.of(ClippingRepresentation.WRITE_NS, ClippingRepresentation.WRITE_NS_Q0S, ClippingRepresentation.WRITE_Q0S);
            outputBam.setPresorted(presorted.contains(clippingRepresentation));
        }
    }

    /**
     * Helper function that adds a seq with name and bases (as bytes) to the list of sequences to be clipped
     *
     * @param name
     * @param bases
     */
    private void addSeqToClip(String name, byte[] bases) {
        SeqToClip clip = new SeqToClip(name, StringUtil.bytesToString(bases));
        sequencesToClip.add(clip);
        logger.info(String.format("Creating sequence clipper %s: %s/%s", clip.name, clip.seq, clip.revSeq));
    }

    /**
     * The reads map function.
     *
     * @param ref  the reference bases that correspond to our read, if a reference was provided
     * @param read the read itself, as a SAMRecord
     * @return the ReadClipper object describing what should be done to clip this read
     */
    public ReadClipper map(ReferenceContext ref, SAMRecord read, ReadMetaDataTracker metaDataTracker) {
        if ( onlyDoRead == null || read.getReadName().equals(onlyDoRead) ) {
            if ( clippingRepresentation == ClippingRepresentation.HARDCLIP_BASES ) {
                read = ReadUtils.replaceSoftClipsWithMatches(read);
            }
            ReadClipper clipper = new ReadClipper(read);

            //
            // run all three clipping modules
            //
            clipBadQualityScores(clipper);
            clipCycles(clipper);
            clipSequences(clipper);
            return clipper;
        }

        return null;
    }

    /**
     * clip sequences from the reads that match all of the sequences in the global sequencesToClip variable.
     * Adds ClippingOps for each clip to clipper.
     *
     * @param clipper
     */
    private void clipSequences(ReadClipper clipper) {
        if (sequencesToClip != null) {                // don't bother if we don't have any sequences to clip
            SAMRecord read = clipper.getRead();

            for (SeqToClip stc : sequencesToClip) {
                // we have a pattern for both the forward and the reverse strands
                Pattern pattern = read.getReadNegativeStrandFlag() ? stc.revPat : stc.fwdPat;
                String bases = read.getReadString();
                Matcher match = pattern.matcher(bases);

                // keep clipping until match.find() says it can't find anything else
                boolean found = true;   // go through at least once
                while (found) {
                    found = match.find();
                    //System.out.printf("Matching %s against %s/%s => %b%n", bases, stc.seq, stc.revSeq, found);
                    if (found) {
                        int start = match.start();
                        int stop = match.end() - 1;
                        ClippingOp op = new ClippingOp(ClippingOp.ClippingType.MATCHES_CLIP_SEQ, start, stop, stc.seq);
                        clipper.addOp(op);
                    }
                }
            }
        }
    }

    /**
     * Convenence function that takes a read and the start / stop clipping positions based on the forward
     * strand, and returns start/stop values appropriate for the strand of the read.
     *
     * @param read
     * @param start
     * @param stop
     * @return
     */
    private Pair<Integer, Integer> strandAwarePositions(SAMRecord read, int start, int stop) {
        if (read.getReadNegativeStrandFlag())
            return new Pair<Integer, Integer>(read.getReadLength() - stop - 1, read.getReadLength() - start - 1);
        else
            return new Pair<Integer, Integer>(start, stop);
    }

    /**
     * clip bases at cycles between the ranges in cyclesToClip by adding appropriate ClippingOps to clipper.
     *
     * @param clipper
     */
    private void clipCycles(ReadClipper clipper) {
        if (cyclesToClip != null) {
            SAMRecord read = clipper.getRead();

            for (Pair<Integer, Integer> p : cyclesToClip) {   // iterate over each cycle range
                int cycleStart = p.first;
                int cycleStop = p.second;

                if (cycleStart < read.getReadLength()) {
                    // only try to clip if the cycleStart is less than the read's length
                    if (cycleStop >= read.getReadLength())
                        // we do tolerate [for convenience) clipping when the stop is beyond the end of the read
                        cycleStop = read.getReadLength() - 1;

                    Pair<Integer, Integer> startStop = strandAwarePositions(read, cycleStart, cycleStop);
                    int start = startStop.first;
                    int stop = startStop.second;

                    ClippingOp op = new ClippingOp(ClippingOp.ClippingType.WITHIN_CLIP_RANGE, start, stop, null);
                    clipper.addOp(op);
                }
            }
        }
    }

    /**
     * Clip bases from the read in clipper from
     * <p/>
     * argmax_x{ \sum{i = x + 1}^l (qTrimmingThreshold - qual)
     * <p/>
     * to the end of the read.  This is blatantly stolen from BWA.
     * <p/>
     * Walk through the read from the end (in machine cycle order) to the beginning, calculating the
     * running sum of qTrimmingThreshold - qual.  While we do this, we track the maximum value of this
     * sum where the delta > 0.  After the loop, clipPoint is either -1 (don't do anything) or the
     * clipping index in the read (from the end).
     *
     * @param clipper
     */
    private void clipBadQualityScores(ReadClipper clipper) {
        SAMRecord read = clipper.getRead();
        int readLen = read.getReadBases().length;
        byte[] quals = read.getBaseQualities();


        int clipSum = 0, lastMax = -1, clipPoint = -1; // -1 means no clip
        for (int i = readLen - 1; i >= 0; i--) {
            int baseIndex = read.getReadNegativeStrandFlag() ? readLen - i - 1 : i;
            byte qual = quals[baseIndex];
            clipSum += (qTrimmingThreshold - qual);
            if (clipSum >= 0 && (clipSum >= lastMax)) {
                lastMax = clipSum;
                clipPoint = baseIndex;
            }
        }

        if (clipPoint != -1) {
            int start = read.getReadNegativeStrandFlag() ? 0 : clipPoint;
            int stop = read.getReadNegativeStrandFlag() ? clipPoint : readLen - 1;
            clipper.addOp(new ClippingOp(ClippingOp.ClippingType.LOW_Q_SCORES, start, stop, null));
        }
    }

    /**
     * reduceInit is called once before any calls to the map function.  We use it here to setup the output
     * bam file, if it was specified on the command line
     *
     * @return
     */
    public ClippingData reduceInit() {
        return new ClippingData(sequencesToClip);
    }

    public ClippingData reduce(ReadClipper clipper, ClippingData data) {
        if ( clipper == null )
            return data;

        SAMRecord clippedRead = clipper.clipRead(clippingRepresentation);
        if (outputBam != null) {
            outputBam.addAlignment(clippedRead);
        } else {
            out.println(clippedRead.format());
        }

        data.nTotalReads++;
        data.nTotalBases += clipper.getRead().getReadLength();
        if (clipper.wasClipped()) {
            data.nClippedReads++;
            for (ClippingOp op : clipper.getOps()) {
                switch (op.type) {
                    case LOW_Q_SCORES:
                        data.incNQClippedBases(op.getLength());
                        break;
                    case WITHIN_CLIP_RANGE:
                        data.incNRangeClippedBases(op.getLength());
                        break;
                    case MATCHES_CLIP_SEQ:
                        data.incSeqClippedBases((String) op.extraInfo, op.getLength());
                        break;
                    default:
                        throw new IllegalStateException("Unexpected Clipping operator type " + op);
                }
            }
        }

        return data;
    }

    public void onTraversalDone(ClippingData data) {
        out.printf(data.toString());
    }

    // --------------------------------------------------------------------------------------------------------------
    //
    // utility classes
    //
    // --------------------------------------------------------------------------------------------------------------

    private static class SeqToClip {
        String name;
        String seq, revSeq;
        Pattern fwdPat, revPat;

        public SeqToClip(String name, String seq) {
            this.name = name;
            this.seq = seq;
            this.fwdPat = Pattern.compile(seq, Pattern.CASE_INSENSITIVE);
            this.revSeq = BaseUtils.simpleReverseComplement(seq);
            this.revPat = Pattern.compile(revSeq, Pattern.CASE_INSENSITIVE);
        }
    }

    public static class ClippingData {
        public long nTotalReads = 0;
        public long nTotalBases = 0;
        public long nClippedReads = 0;
        public long nClippedBases = 0;
        public long nQClippedBases = 0;
        public long nRangeClippedBases = 0;
        public long nSeqClippedBases = 0;

        HashMap<String, Long> seqClipCounts = new HashMap<String, Long>();

        public ClippingData(List<SeqToClip> clipSeqs) {
            for (SeqToClip clipSeq : clipSeqs) {
                seqClipCounts.put(clipSeq.seq, 0L);
            }
        }

        public void incNQClippedBases(int n) {
            nQClippedBases += n;
            nClippedBases += n;
        }

        public void incNRangeClippedBases(int n) {
            nRangeClippedBases += n;
            nClippedBases += n;
        }

        public void incSeqClippedBases(final String seq, int n) {
            nSeqClippedBases += n;
            nClippedBases += n;
            seqClipCounts.put(seq, seqClipCounts.get(seq) + n);
        }

        public String toString() {
            StringBuilder s = new StringBuilder();

            s.append(Utils.dupString('-', 80) + "\n");
            s.append(String.format("Number of examined reads              %d%n", nTotalReads));
            s.append(String.format("Number of clipped reads               %d%n", nClippedReads));
            s.append(String.format("Percent of clipped reads              %.2f%n", (100.0 * nClippedReads) / nTotalReads));
            s.append(String.format("Number of examined bases              %d%n", nTotalBases));
            s.append(String.format("Number of clipped bases               %d%n", nClippedBases));
            s.append(String.format("Percent of clipped bases              %.2f%n", (100.0 * nClippedBases) / nTotalBases));
            s.append(String.format("Number of quality-score clipped bases %d%n", nQClippedBases));
            s.append(String.format("Number of range clipped bases         %d%n", nRangeClippedBases));
            s.append(String.format("Number of sequence clipped bases      %d%n", nSeqClippedBases));

            for (Map.Entry<String, Long> elt : seqClipCounts.entrySet()) {
                s.append(String.format("  %8d clip sites matching %s%n", elt.getValue(), elt.getKey()));
            }

            s.append(Utils.dupString('-', 80) + "\n");
            return s.toString();
        }
    }
}