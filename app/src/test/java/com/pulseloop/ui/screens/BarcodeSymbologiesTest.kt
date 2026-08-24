package com.pulseloop.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanner's accepted symbology set — iOS restricts the VisionKit data scanner to
 * [.ean13, .ean8, .upce, .code128] (BarcodeScannerSheet.swift:56), the four Open Food Facts
 * is keyed on. Anything else (QR, PDF417, Data Matrix, …) must stay out of the set so a
 * poster QR code in frame can never be delivered as a "barcode".
 */
class BarcodeSymbologiesTest {

    @Test
    fun acceptsExactlyTheFourOpenFoodFactsSymbologies() {
        assertEquals(
            setOf("EAN-13", "EAN-8", "UPC-E", "Code-128"),
            BarcodeSymbologies.accepted,
        )
    }

    @Test
    fun acceptsEachOfTheFour() {
        assertTrue(BarcodeSymbologies.isAccepted(BarcodeSymbologies.EAN13))
        assertTrue(BarcodeSymbologies.isAccepted(BarcodeSymbologies.EAN8))
        assertTrue(BarcodeSymbologies.isAccepted(BarcodeSymbologies.UPCE))
        assertTrue(BarcodeSymbologies.isAccepted(BarcodeSymbologies.CODE128))
    }

    @Test
    fun rejectsSymbologiesIosDoesNotScan() {
        assertFalse(BarcodeSymbologies.isAccepted("QR"))
        assertFalse(BarcodeSymbologies.isAccepted("PDF417"))
        assertFalse(BarcodeSymbologies.isAccepted("Data Matrix"))
        // UPC-A rides in as an EAN-13 with a leading zero; it is not a separate accepted name.
        assertFalse(BarcodeSymbologies.isAccepted("UPC-A"))
        assertFalse(BarcodeSymbologies.isAccepted(""))
    }
}
