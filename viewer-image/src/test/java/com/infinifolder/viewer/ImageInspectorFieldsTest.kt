package com.infinifolder.viewer

import com.infinifolder.preview.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ImageInspectorFieldsTest {
    @Test fun allLegacyLabelsUnitsAndOrderRemain() {
        assertEquals(listOf("Camera: Make Model", "Date: 2026:10:31 12:00:00", "Exposure: 1/125s", "Aperture: f/2.8", "ISO: 200", "Focal Length: 50/1mm"),
            ImageExifFields("Make", "Model", "2026:10:31 12:00:00", "1/125", "2.8", "200", "50/1").inspectorLines())
    }
    @Test fun blankAndMissingValuesAreNotDisplayed() {
        assertTrue(ImageExifFields(make = " ", model = "", dateTime = "\t", exposureTime = "", fNumber = " ", iso = "", focalLength = " ").inspectorLines().isEmpty())
    }
    @Test fun makeOnlyKeepsLegacyCameraLabel() {
        assertEquals(listOf("Camera: Make"), ImageExifFields(make = "Make").inspectorLines())
    }
    @Test fun modelOnlyKeepsLegacySpacing() {
        assertEquals(listOf("Camera:  Model"), ImageExifFields(model = "Model").inspectorLines())
    }
    @Test fun gpsKeepsFloatPrecisionFiveDecimalsAndLocale() {
        val prior = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val fields = ImageExifFields(location = ImageLocation(41.123456f, -87.65432f))
            assertEquals(listOf("GPS: 41.12346, -87.65432"), fields.inspectorLines())
            Locale.setDefault(Locale.GERMANY)
            assertEquals(listOf("GPS: 41,12346, -87,65432"), fields.inspectorLines())
        } finally { Locale.setDefault(prior) }
    }
}
