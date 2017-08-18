# maglev-hash-java

## Overview

This is a java implementation of maglev hash algorithm. Beside a few notable differences, it is mostly a transcript from https://github.com/kkdai/maglev, the author of which gave a very good explanation of of how it works.

WARNING: This is NOT the exact version used in production and is provided "as-is". 

## Implemetaion Differences

1. This implementation employs a lazy approach to calculate the permutations and fill the lookup table just in time so that computation costs might be spread over time, hoping for a swift startup. Besides, in a large cluster, permuations of some servers might never need to be fully calculated in their lifetime.

2. Permutations are stored as 16bit char as unsigned integer to conserve memory (approx 64MB of memory use for permutations of 512 servers with the default size of lookup of 65521). Permutations are not initialized beforehand, therefore, server array index has to be stored as +1, e.g, 1 is cells[0]  and 0 means not yet filled. 

3. Permutation is calculated using the last value to get rid of CPU multiplications operation and type promotion resulting from overflow.

There are known approach for further memory optimizatione, readers might try to figure that that out.

## Install

Import as Maven project, dependencies are Guava, slf4j/logback, junit.

Run the HashMaglevUT to see the debug results, which prints a lot of useful information including disruption rate to help you follow this hashing method.
