package acx.lam;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

public class HashMaglev16Balancer<T extends Cell> {

    private static final Logger logger = LoggerFactory.getLogger(HashMaglev16Balancer.class);

    private static final Charset KEY_ENCODING = StandardCharsets.US_ASCII;
    private static final Charset INPUT_ENCODING = StandardCharsets.UTF_8;

    private static final int MAX_LOOKUP =  65521;
    public static final int SPARE = 20;

    private int nServers;
    private int mSizeLookup;
    private Comparator<T> comparator;

    private ArrayList<T> cells;
    private ArrayList<CellState<T>> cellStates;

    private volatile char[] lookup;
    private static final HashFunction HASH_INPUT = Hashing.murmur3_32(0xaceaceac);
    private static final HashFunction HASH_OFFSET = Hashing.murmur3_32(0xdeadbabe);
    private static final HashFunction HASH_SKIP = Hashing.murmur3_32(0xdeadbeaf);

    private  ArrayList<Cell> cellsBackup; //for add/remove stats only
    private  int lastDelta;                //for add/remove stats only

    private static class CellState<T> {

        //will not change
        private char[] row;
        private int last;

        //will change
        private int nextPos;
        private int skip;
        private int offset;

        private void reset() {
            this.nextPos = 0;
            this.last = offset;
        }
    }

    public HashMaglev16Balancer(List<T> inputCells, Integer mSizeLookup, Comparator<T> comparator) throws Exception {

        if (inputCells == null || inputCells.size() == 0) {
            throw new Exception("Empty cells");
        }

        if (mSizeLookup == null) {
            mSizeLookup = MAX_LOOKUP;
        }

        this.comparator = comparator;

        if (mSizeLookup > MAX_LOOKUP || inputCells.size() > MAX_LOOKUP) {
            throw new IllegalArgumentException("This implementation allows a max of 65521 servers or lookups");
        }

        this.nServers = inputCells.size();
        logger.debug("number of cells {}", nServers);

        int initialCapacity = nServers + SPARE;

        this.cells = new ArrayList<>(initialCapacity);
        cells.addAll(inputCells);

       this.mSizeLookup = mSizeLookup;

        long start = System.currentTimeMillis();

        Collections.sort(cells, comparator);

        //isPrime(mSizeLookup); //not yet implemented
        generateServerStates(initialCapacity);
        generateLookupLazily();
        printStats(start);
    }

    public int addCells(List<T> newCells) {

        if (newCells == null || newCells.size() == 0) {
            logger.debug("addCells: nothing to add");
            return 0;
        }

        long start = System.currentTimeMillis();
        int added = 0;

        logger.debug("start adding cells");

        if(logger.isErrorEnabled()){
            cellsBackup = (ArrayList<Cell>) cells.clone();
        }

        synchronized (this) {

            for (T newCell : newCells) {
                int pos = Collections.binarySearch(cells, newCell, this.comparator);

                if (pos >= 0) {
                    logger.debug("cell already exists");
                    continue;
                }

                int insertPoint = -(pos + 1);

                CellState state = this.createState(newCell.getUniqueKey());
                cells.add(insertPoint, newCell);
                cellStates.add(insertPoint, state);
                initSomeColumns(state);

                added++;
                nServers++;
            }

            logger.debug("time used moving arrays: {}ms ", (System.currentTimeMillis() - start));

            if(added > 0 ) {
                resetCellState();
                this.lastDelta = added;
                this.generateLookupLazily();
            }
        }

        logger.debug("{} cells appended  to the cell list", added);
        logger.debug("lookup regen completed, total time used: {}ms ", (System.currentTimeMillis() - start));
        return added;

    }

    private void initSomeColumns(CellState state) {
        for(int i = 1 ; i < mSizeLookup / nServers; ++i){
            state.last = (state.last + state.skip)% mSizeLookup;
            state.row[i] = (char)(state.last+1);
        }
    }

    private void resetCellState() {
        for(CellState<T> state: cellStates){
            state.reset();
        }
    }

    public int removeCells(List<T> removeCells) {

        long start = System.currentTimeMillis();

        if (removeCells == null || removeCells.size() == 0) {
            logger.debug("addCells: nothing to remove");
            return 0;
        }

        if (cells.size() == 0){
            logger.debug("already empty");
            return 0;
        }

        int removed = 0;
        logger.debug("start removing cells");

        if(logger.isErrorEnabled()){
            cellsBackup = (ArrayList<Cell>) cells.clone();
        }

        synchronized (this) {

            for (T removeCell : removeCells) {
                int pos = Collections.binarySearch(cells, removeCell, this.comparator);

                if (pos < 0) {
                    logger.debug("cell not exists");
                    continue;
                }

                cells.remove(pos);
                cellStates.remove(pos);
                nServers--;
                removed++;
            }

            logger.debug("time used moving arrays: {}ms ", (System.currentTimeMillis() - start));

            if(removed > 0) {
                resetCellState();
                this.lastDelta = -removed;
                this.generateLookupLazily();
            }
        }

        logger.debug("{} cells removed from the cell list", removed);
        logger.debug("lookup regen done, total time used removing cells: {}ms ", (System.currentTimeMillis() - start));
        return removed;
    }


    private void generateServerStates(int initialCapacity) {

        cellStates = new ArrayList<>(initialCapacity);

        for (int i = 0; i < nServers; ++i) {

            String key = cells.get(i).getUniqueKey();
            CellState state = createState(key);
            cellStates.add(state);

        }
    }

    private CellState createState(String key) {
        CellState state = new CellState();
        state.offset = (int) (HASH_OFFSET.hashString(key, KEY_ENCODING).padToLong() % mSizeLookup);
        state.skip = (int) (HASH_SKIP.hashString(key, KEY_ENCODING).padToLong() % (mSizeLookup - 1) + 1);
        state.last = state.offset;

        char[] row = new char[mSizeLookup];
        row[0] = (char) (++state.offset); // initialized first column ,store as +1
        state.row = row;
        return state;
    }

    private void generateLookupLazily() {

        char[] tmpLookup = new char[mSizeLookup];

        int filled = 0;
        fillLookup(tmpLookup, filled);


        if(logger.isDebugEnabled()) {
            if (lookup != null){
                int changed = 0;
                logger.debug("lookup changed:");


                for (int i = 0; i < mSizeLookup; i++) {
                    if(cellsBackup.get(lookup[i]-1) != cells.get(tmpLookup[i]-1)){
                        changed++;
                        // uncomment to show disruptions
                       // logger.debug("{}:{}->{}", i, cellsBackup.get(lookup[i]-1).getUniqueKey(), cells.get(tmpLookup[i]-1).getUniqueKey());
                    }
                }

                cellsBackup = null;

                float permChanged = ((float)changed * 100)/(float)mSizeLookup;
                float best;

                if (lastDelta > 0) {
                    best =((float) (this.lastDelta * 100)) / (float) (nServers );
                } else {
                    best = ((float) ((-this.lastDelta) * 100)) / (float) (nServers - lastDelta );
                }

                logger.debug("total num of changes: {}, permchanged {}%, best {}% ,disruption (delta - best) {}%" , changed, permChanged, best, (permChanged - best) );

                lastDelta = 0;
            }

        }

        this.lookup = tmpLookup; // swap;
    }

    private void fillLookup(char[] tmpLookup, int filled) {
        outer: while(true) {
            for (int i = 0; i < nServers; ++i) {

                CellState state = cellStates.get(i);
                while(state.nextPos < mSizeLookup) {

                    int c = getPermutation(state);
                    state.nextPos++;
                    if (tmpLookup[c] == 0) { //found
                        tmpLookup[c] = (char) (i + 1); //Store i + 1 to avoid initialization
                        filled++;

                        if(filled == mSizeLookup){
                            return; //break outer
                        } else{
                            break; // next server
                        }
                    }
                }

            }

        }
    }

    public T getInstance(String key) {

        if (key == null || nServers == 0) {
            return null;
        }
        if (nServers == 1) {
            return cells.get(0);
        }

        int index = (int) ((HASH_INPUT.hashString(key, INPUT_ENCODING).padToLong()) % mSizeLookup);
        return cells.get(lookup[index] - 1);

    }

    private int getPermutation(CellState state) {
        char c = state.row[state.nextPos];

        if (c == 0) {

            if (state.nextPos == 0){
                return state.offset;
            }

            int perm = (state.last + state.skip) % mSizeLookup;
            state.last = perm;
            state.row[state.nextPos] = (char) (perm + 1); //store to perm as i+1
            return perm;
        }

        return --c;

    }

    private void printStats(long start) {

        logger.debug("time used {}ms", (System.currentTimeMillis() - start));

       // printPermutationAndLookup(); //uncomment to see all the permutations and lookup

        int totalUsed = 0;
        for (CellState state : cellStates) {
            totalUsed += state.nextPos;
        }

        float occupation = (float) totalUsed * 100 / (float) MAX_LOOKUP / (float) nServers;

        logger.debug("total steps {}, percentage to all permutations {}%", totalUsed, occupation);

        logger.debug("-----------------------------------");

    }

    private void printPermutationAndLookup() {
        StringBuilder sb = new StringBuilder(mSizeLookup * 2 + 10);

        for(int i = 0 ; i < nServers ; ++i){

            for(int j = 0; j < mSizeLookup; ++j){
                sb.append(cellStates.get(i).row[j] -1);
                sb.append(',');
            }

            sb.append("\r\n");
        }

        logger.debug(sb.toString());

        sb.setLength(0);

        sb.append('[');

        for (char entry : lookup) {
            sb.append((int) entry);
            sb.append(',');
        }

        sb.append(']');

        logger.debug("lookup table:" + sb.toString());
    }
}
