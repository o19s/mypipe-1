package mypipe.producer.cassandra

import com.netflix.astyanax.{ Serializer, AstyanaxContext, Keyspace, MutationBatch }
import mypipe.api.{ DeleteMutation, UpdateMutation, InsertMutation }
import com.netflix.astyanax.model.ColumnFamily
import com.netflix.astyanax.serializers.{ TimeUUIDSerializer, LongSerializer, StringSerializer }
import com.netflix.astyanax.impl.AstyanaxConfigurationImpl
import com.netflix.astyanax.connectionpool.NodeDiscoveryType
import com.netflix.astyanax.connectionpool.impl.{ CountingConnectionPoolMonitor, ConnectionPoolConfigurationImpl }
import com.netflix.astyanax.thrift.ThriftFamilyFactory
import com.netflix.astyanax.util.TimeUUIDUtils

trait Mapping[T] {
  def map(mutation: InsertMutation): Option[T]
  def map(mutation: UpdateMutation): Option[T]
  def map(mutation: DeleteMutation): Option[T]
}

class CassandraProfileMapping extends Mapping[MutationBatch] {

  // create keyspace logs;
  // create column family profile_counters with default_validation_class=CounterColumnType;
  // create column family wswl with comparator=TimeUUIDType;

  import CassandraMappings.{ mutation, columnFamily }
  import CassandraMappings.Serializers._

  def map(mutation: UpdateMutation): Option[MutationBatch] = None
  def map(mutation: DeleteMutation): Option[MutationBatch] = None

  def map(i: InsertMutation): Option[MutationBatch] = {

    (i.table.db, i.table.name) match {

      case ("logging", "WhosSeenWhoLog") ⇒ {

        val row = i.rows.head.columns
        val rowKey = row("profile_id").value.toString
        val time = row("log_time").value.asInstanceOf[Int].toLong
        val timeUUID = TimeUUIDUtils.getTimeUUID(time)

        val wswl = columnFamily(
          name = "wswl",
          keySer = STRING,
          colSer = TIMEUUID)

        val counters = columnFamily(
          name = "profile_counters",
          keySer = STRING,
          colSer = STRING)

        val m = mutation("logs")

        m.withRow(wswl, rowKey)
          .putColumn(timeUUID, row("listed_profile_id").value.toString)

        m.withRow(counters, rowKey)
          .incrementCounterColumn("views", 1)

        Some(m)
      }

      case ("logging", "foo") ⇒ {
        None
      }

      case x ⇒ {
        None
      }
    }
  }
}

object CassandraMappings {

  // TODO: get these from the config
  val clusterName: String = "Test Cluster"
  val seeds: String = "127.0.0.1:9160"
  val port: Int = 9160
  val maxConnsPerHost: Int = 1

  val keyspaces = scala.collection.mutable.HashMap[String, Keyspace]()
  val columnFamilies = scala.collection.mutable.HashMap[String, ColumnFamily[_, _]]()
  val mutations = scala.collection.mutable.HashMap[String, MutationBatch]()

  object Serializers {
    val TIMEUUID = TimeUUIDSerializer.get()
    val STRING = StringSerializer.get()
    val LONG = LongSerializer.get()
  }

  def columnFamily[R, C](name: String, keySer: Serializer[R], colSer: Serializer[C]): ColumnFamily[R, C] = {
    columnFamilies.getOrElseUpdate(name, createColumnFamily[R, C](name, keySer, colSer)).asInstanceOf[ColumnFamily[R, C]]
  }

  def mutation(keyspace: String): MutationBatch = {
    mutations.getOrElseUpdate(keyspace, createMutation(keyspace))
  }

  protected def createMutation(keyspace: String): MutationBatch = {
    val ks = keyspaces.getOrElseUpdate(keyspace, createKeyspace(keyspace))
    ks.prepareMutationBatch()
  }

  protected def createColumnFamily[R, C](cf: String, keySer: Serializer[R], colSer: Serializer[C]): ColumnFamily[R, C] = {
    new ColumnFamily[R, C](cf, keySer, colSer)
  }

  protected def createKeyspace(keyspace: String): Keyspace = {

    val context: AstyanaxContext[Keyspace] = new AstyanaxContext.Builder()
      .forCluster(clusterName)
      .forKeyspace(keyspace)
      .withAstyanaxConfiguration(new AstyanaxConfigurationImpl()
        .setDiscoveryType(NodeDiscoveryType.RING_DESCRIBE))
      .withConnectionPoolConfiguration(new ConnectionPoolConfigurationImpl(s"$clusterName-$keyspace-connpool")
        .setPort(port)
        .setMaxConnsPerHost(maxConnsPerHost)
        .setSeeds(seeds))
      .withConnectionPoolMonitor(new CountingConnectionPoolMonitor())
      .buildKeyspace(ThriftFamilyFactory.getInstance())

    context.start()
    context.getClient()
  }
}

object CassandraDSLTest extends App {

}