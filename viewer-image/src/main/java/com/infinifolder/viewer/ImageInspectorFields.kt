package com.infinifolder.viewer

import com.infinifolder.preview.ImageExifFields

/** Keep labels/formatting in the viewer and the library's raw values unchanged. */
internal fun ImageExifFields.inspectorLines(): List<String> = buildList {
    if (!make.isNullOrBlank() || !model.isNullOrBlank()) add("Camera: ${make ?: ""} ${model ?: ""}".trim())
    if (!dateTime.isNullOrBlank()) add("Date: $dateTime")
    if (!exposureTime.isNullOrBlank()) add("Exposure: ${exposureTime}s")
    if (!fNumber.isNullOrBlank()) add("Aperture: f/$fNumber")
    if (!iso.isNullOrBlank()) add("ISO: $iso")
    if (!focalLength.isNullOrBlank()) add("Focal Length: ${focalLength}mm")
    location?.let { add("GPS: %.5f, %.5f".format(it.latitude, it.longitude)) }
}
