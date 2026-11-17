package com.igviewer.cleaner.data

/**
 * How much to shrink a bitmap while decoding it.
 *
 * `BitmapFactory` applies `inSampleSize` as a power-of-two divisor, so the only
 * values worth asking for are 1, 2, 4, 8, … Anything else is silently rounded
 * down by the decoder, and 0 is rejected outright. A 4000x3000 phone photo
 * decoded at full size is ~48 MB of heap; at sample 2 it is 12 MB, which is the
 * difference between a preview and an OutOfMemoryError on a low-end device.
 *
 * [maxDimension] is the longest side the caller is willing to keep (a screen
 * dimension, or the 192 px of a list thumbnail).
 */
fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
    val longest = maxOf(width, height)
    var sample = 1
    // `longest / sample` is integer division on purpose: the loop stops at the
    // first power of two that fits, never overshoots, and cannot overflow.
    while (longest / sample > maxDimension) sample *= 2
    return sample
}
