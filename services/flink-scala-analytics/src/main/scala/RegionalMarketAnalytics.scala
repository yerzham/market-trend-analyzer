import mypackage.message.{FinancialTick, EMAResult, BuyAdvisory}

import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows
import org.apache.flink.streaming.api.windowing.time.Time
import java.time.{LocalDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter

object RegionalMarketAnalytics {

  def main(args: Array[String]): Unit = {
    // Define regional jobs
    val regions = Seq(
      ("Region1", "FR", "FR-analytics")
    )

    regions.foreach { case (region, inputTopic, outputTopic) =>
      createJob(region, inputTopic, outputTopic, parallelism = 1)
    }
  }

  def createJob(
      region: String,
      inputTopic: String,
      outputTopic: String,
      parallelism: Int
  ): Unit = {
    // Setup Flink execution environment, event time, and parallelism
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    env.setParallelism(parallelism)

    // Kafka consumer properties
    val kafkaConsumerProps = new java.util.Properties()
    kafkaConsumerProps.put(
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
      "kafka:9092"
    )
    kafkaConsumerProps.put(
      ConsumerConfig.GROUP_ID_CONFIG,
      s"flink-market-analytics-$region"
    )

    // Create Kafka consumer with Protobuf deserializer
    val kafkaConsumer = new FlinkKafkaConsumer[FinancialTick](
      inputTopic,
      new FinancialTickDeserializer(),
      kafkaConsumerProps
    )

    // Parse Market Tick Data
    val FinancialTickStream = env
      .addSource(kafkaConsumer)
      .assignTimestampsAndWatermarks(new FinancialTickTimestampExtractor())

    // Set up 5-minute tumbling windows starting at midnight
    val windowSize = Time.minutes(1)
    val windowOffset = Time.hours(0) // Adjust if needed

    // Compute EMA for each symbol
    val emaStream = FinancialTickStream
      .keyBy(_.id)
      .window(TumblingEventTimeWindows.of(windowSize, windowOffset))
      .apply(new EMACalculator)

    // Detect crossovers and generate buy advisories
    val buyAdvisoryStream = emaStream
      .keyBy(_.symbol)
      .flatMap(new CrossoverDetector)

    // Kafka producer properties for buy advisories
    val buyAdvisoryProducerProps = new java.util.Properties()
    buyAdvisoryProducerProps.put(
      ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
      "kafka:9092"
    )
    buyAdvisoryProducerProps.put(
      "compression.type",
      "snappy"
    )

    // Create Kafka producer for buy advisories
    val buyAdvisoryProducer = new FlinkKafkaProducer[BuyAdvisory](
      outputTopic,
      new ProtobufSerializer(),
      buyAdvisoryProducerProps
    )

    // Add sink to Kafka for buy advisories
    buyAdvisoryStream.addSink(buyAdvisoryProducer)

    // Execute the Flink job
    env.execute(s"Market Analytics Job for Region $region")
  }
}