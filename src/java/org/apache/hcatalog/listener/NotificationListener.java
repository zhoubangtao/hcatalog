/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hcatalog.listener;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;

import javax.jdo.PersistenceManager;
import javax.jdo.Query;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.HiveMetaStore.HMSHandler;
import org.apache.hadoop.hive.metastore.MetaStoreEventListener;
import org.apache.hadoop.hive.metastore.ObjectStore;
import org.apache.hadoop.hive.metastore.RawStore;
import org.apache.hadoop.hive.metastore.HiveMetaStore.HMSHandler.Command;
import org.apache.hadoop.hive.metastore.api.HiveObjectRef;
import org.apache.hadoop.hive.metastore.api.InvalidOperationException;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Order;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.events.AddPartitionEvent;
import org.apache.hadoop.hive.metastore.events.CreateDatabaseEvent;
import org.apache.hadoop.hive.metastore.events.CreateTableEvent;
import org.apache.hadoop.hive.metastore.events.DropDatabaseEvent;
import org.apache.hadoop.hive.metastore.events.DropPartitionEvent;
import org.apache.hadoop.hive.metastore.events.DropTableEvent;
import org.apache.hcatalog.common.HCatConstants;

/**
 * Implementation of {@link org.apache.hadoop.hive.metastore.MetaStoreEventListener}
 * It sends message on two type of topics. One has name of form dbName.tblName
 * On this topic, two kind of messages are sent: add/drop partition and 
 * finalize_partition message.
 * Second topic has name "HCAT" and messages sent on it are: add/drop database
 * and add/drop table.
 * All messages also has a property named "HCAT_EVENT" set on them whose value
 * can be used to configure message selector on subscriber side.  
 */
public class NotificationListener extends MetaStoreEventListener{

	private static final Log LOG = LogFactory.getLog(NotificationListener.class);
	private Session session;
	private Connection conn;

	/**
	 * Create message bus connection and session in constructor.
	 */
	public NotificationListener(final Configuration conf) {

		super(conf);
		try {
			Context jndiCntxt = new InitialContext();
			ConnectionFactory connFac = (ConnectionFactory)jndiCntxt.lookup("ConnectionFactory");
			conn = connFac.createConnection();
			conn.start();
			// We want message to be sent when session commits, thus we run in
			// transacted mode.
			session = conn.createSession(true, Session.SESSION_TRANSACTED);

		} catch (NamingException e) {
			LOG.error("JNDI error while setting up Message Bus connection. " +
					"Please make sure file named 'jndi.properties' is in " +
					"classpath and contains appropriate key-value pairs.",e);
		}
		catch (JMSException e) {
			LOG.error("Failed to initialize connection to message bus",e);
		}
		catch(Throwable t){
			LOG.error("HCAT Listener failed to load",t);
		}
	}

	@Override
	public void onAddPartition(AddPartitionEvent partitionEvent) throws MetaException {
		// Subscriber can get notification of newly add partition in a 
		// particular table by listening on a topic named "dbName.tableName" 
		// and message selector string as "HCAT_EVENT = HCAT_ADD_PARTITION" 
		if(partitionEvent.getStatus()){

			Partition partition = partitionEvent.getPartition();
			String topicName;
			try {
				topicName = partitionEvent.getHandler().get_table(
						partition.getDbName(), partition.getTableName()).getParameters().get(HCatConstants.HCAT_MSGBUS_TOPIC_NAME);
			} catch (NoSuchObjectException e) {
				throw new MetaException(e.toString());
			}
			send(partition, topicName, HCatConstants.HCAT_ADD_PARTITION_EVENT);			
		}

	}

	@Override
	public void onDropPartition(DropPartitionEvent partitionEvent) throws MetaException {
		// Subscriber can get notification of dropped partition in a 
		// particular table by listening on a topic named "dbName.tableName" 
		// and message  selector string as "HCAT_EVENT = HCAT_DROP_PARTITION" 

		// Datanucleus throws NPE when we try to serialize a partition object
		// retrieved from metastore. To workaround that we reset following objects

		if(partitionEvent.getStatus()){
			Partition partition = partitionEvent.getPartition();
			StorageDescriptor sd = partition.getSd();
			sd.setBucketCols(new ArrayList<String>());
			sd.setSortCols(new ArrayList<Order>());
			sd.setParameters(new HashMap<String, String>());
			sd.getSerdeInfo().setParameters(new HashMap<String, String>());
			String topicName;
			try {
				topicName = partitionEvent.getHandler().get_table(
						partition.getDbName(), partition.getTableName()).getParameters().get(HCatConstants.HCAT_MSGBUS_TOPIC_NAME);
			} catch (NoSuchObjectException e) {
				throw new MetaException(e.toString());
			}
			send(partition, topicName, HCatConstants.HCAT_DROP_PARTITION_EVENT);
		}
	}

	@Override
	public void onCreateDatabase(CreateDatabaseEvent dbEvent) throws MetaException {
		// Subscriber can get notification about addition of a database in HCAT
		// by listening on a topic named "HCAT" and message selector string
		// as "HCAT_EVENT = HCAT_ADD_DATABASE" 
		if(dbEvent.getStatus())
			send(dbEvent.getDatabase(),HCatConstants.HCAT_TOPIC,HCatConstants.HCAT_ADD_DATABASE_EVENT);
	}

	@Override
	public void onDropDatabase(DropDatabaseEvent dbEvent) throws MetaException {
		// Subscriber can get notification about drop of a database in HCAT
		// by listening on a topic named "HCAT" and message selector string
		// as "HCAT_EVENT = HCAT_DROP_DATABASE" 
		if(dbEvent.getStatus())
			send(dbEvent.getDatabase(),HCatConstants.HCAT_TOPIC,HCatConstants.HCAT_DROP_DATABASE_EVENT);
	}

	@Override
	public void onCreateTable(CreateTableEvent tableEvent) throws MetaException {
		// Subscriber can get notification about addition of  a table in HCAT
		// by listening on a topic named "HCAT" and message selector string
		// as "HCAT_EVENT = HCAT_ADD_TABLE" 
		if(tableEvent.getStatus()){
			if(tableEvent.getStatus()){
				Table tbl = tableEvent.getTable();
				Table newTbl = tbl.deepCopy();
				HMSHandler handler = tableEvent.getHandler();
				String namingPolicy = handler.getHiveConf().get(HCatConstants.HCAT_MSGBUS_TOPIC_NAMING_POLICY, "tablename");
				newTbl.getParameters().put(HCatConstants.HCAT_MSGBUS_TOPIC_NAME, getTopicNameForParts(namingPolicy, tbl.getDbName(), tbl.getTableName()));
				try {
					handler.alter_table(tbl.getDbName(), tbl.getTableName(), newTbl);
				} catch (InvalidOperationException e) {
					throw new MetaException(e.toString());
				}
				send(tableEvent.getTable(),HCatConstants.HCAT_TOPIC+"."+tbl.getDbName(), HCatConstants.HCAT_ADD_TABLE_EVENT);
			}
		}	
	}

	private String getTopicNameForParts(String namingPolicy, String dbName, String tblName){
		// we only have one policy now, so ignore policy param for now.
		return HCatConstants.HCAT_TOPIC+"."+dbName+"."+tblName;
	}

	@Override
	public void onDropTable(DropTableEvent tableEvent) throws MetaException {
		// Subscriber can get notification about drop of a  table in HCAT
		// by listening on a topic named "HCAT" and message selector string
		// as "HCAT_EVENT = HCAT_DROP_TABLE" 

		// Datanucleus throws NPE when we try to serialize a table object
		// retrieved from metastore. To workaround that we reset following objects

		if(tableEvent.getStatus()){
			Table table = tableEvent.getTable();
			StorageDescriptor sd = table.getSd();
			sd.setBucketCols(new ArrayList<String>());
			sd.setSortCols(new ArrayList<Order>());
			sd.setParameters(new HashMap<String, String>());
			sd.getSerdeInfo().setParameters(new HashMap<String, String>());
			send(table,HCatConstants.HCAT_TOPIC+"."+table.getDbName(), HCatConstants.HCAT_DROP_TABLE_EVENT);	
		}
	}

	/**
	 * @param msgBody is the metastore object. It is sent in full such that
	 * if subscriber is really interested in details, it can reconstruct it fully.
	 * In case of finalize_partition message this will be string specification of 
	 * the partition.
	 * @param topicName is the name on message broker on which message is sent.
	 * @param event is the value of HCAT_EVENT property in message. It can be 
	 * used to select messages in client side. 
	 */
	private void send(Serializable msgBody, String topicName, String event){

		if(null == session){
			// If we weren't able to setup the session in the constructor
			// we cant send message in any case.
			LOG.error("Invalid session. Failed to send message on topic: "+
					topicName + " event: "+event);
			return;
		}

		try{
			// Topics are created on demand. If it doesn't exist on broker it will
			// be created when broker receives this message.
			Destination topic = session.createTopic(topicName);
			MessageProducer producer = session.createProducer(topic);
			ObjectMessage msg = session.createObjectMessage(msgBody);
			msg.setStringProperty(HCatConstants.HCAT_EVENT, event);
			producer.send(msg);
			// Message must be transacted before we return.
			session.commit();
		} catch(Exception e){
			// Gobble up the exception. Message delivery is best effort.
			LOG.error("Failed to send message on topic: "+topicName + 
					" event: "+event , e);
		}
	}

	@Override
	protected void finalize() throws Throwable {
		// Close the connection before dying.
		try {
			if(conn != null) {
				conn.close();
			}
		} catch (Exception ignore) {
			LOG.info("Failed to close message bus connection.", ignore);
		}
	}
}
