package org.radarbase.connect.upload.converter.altoida

import org.radarbase.connect.upload.converter.CsvRecordConverter
import org.radarbase.connect.upload.converter.TopicData
import org.radarcns.connector.upload.altoida.AltoidaAttitude

class AltoidaAttitudeConverter(override val sourceType: String = "altoida_attitude", val topic: String = "connect_upload_altoida_attitude")
    : CsvRecordConverter(sourceType) {

    override fun validateHeaderSchema(csvHeader: List<String>) =
            listOf("TIMESTAMP", "PITCH", "ROLL", "YAW") == (csvHeader)

    override fun convertLineToRecord(lineValues: Map<String, String>, timeReceived: Double): TopicData? {
        val time = lineValues["TIMESTAMP"]?.toDouble()
        val attitude = AltoidaAttitude(
                time,
                timeReceived,
                lineValues["PITCH"]?.toFloat(),
                lineValues["ROLL"]?.toFloat(),
                lineValues["YAW"]?.toFloat()
        )

        return TopicData(false, topic, attitude)
    }
}