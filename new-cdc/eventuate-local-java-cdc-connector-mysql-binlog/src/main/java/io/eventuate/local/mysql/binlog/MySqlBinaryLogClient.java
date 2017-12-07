package io.eventuate.local.mysql.binlog;


import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.*;
import com.github.shyiko.mysql.binlog.event.deserialization.EventDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.NullEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.WriteRowsEventDataDeserializer;
import io.eventuate.local.common.BinLogEvent;
import io.eventuate.local.common.BinlogFileOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class MySqlBinaryLogClient<M extends BinLogEvent> {

  private String name;

  private BinaryLogClient client;
  private long binlogClientUniqueId;

  private final String dbUserName;
  private final String dbPassword;
  private final String host;
  private final int port;

  private final IWriteRowsEventDataParser<M> writeRowsEventDataParser;

  private final String sourceTableName;
  private final Map<Long, TableMapEventData> tableMapEventByTableId = new HashMap<>();
  private String binlogFilename;
  private long offset;

  private int connectionTimeoutInMilliseconds;
  private int maxAttemptsForBinlogConnection;

  private Logger logger = LoggerFactory.getLogger(this.getClass());

  public MySqlBinaryLogClient(IWriteRowsEventDataParser<M> writeRowsEventDataParser,
                              String dbUserName,
                              String dbPassword,
                              String host,
                              int port,
                              long binlogClientUniqueId,
                              String sourceTableName,
                              String clientName,
                              int connectionTimeoutInMilliseconds,
                              int maxAttemptsForBinlogConnection) {
    this.writeRowsEventDataParser = writeRowsEventDataParser;
    this.binlogClientUniqueId = binlogClientUniqueId;
    this.dbUserName = dbUserName;
    this.dbPassword = dbPassword;
    this.host = host;
    this.port = port;
    this.sourceTableName = sourceTableName;
    this.name = clientName;
    this.connectionTimeoutInMilliseconds = connectionTimeoutInMilliseconds;
    this.maxAttemptsForBinlogConnection = maxAttemptsForBinlogConnection;
  }

  public void start(Optional<BinlogFileOffset> binlogFileOffset, Consumer<M> eventConsumer) {

    client = new BinaryLogClient(host, port, dbUserName, dbPassword);
    client.setServerId(binlogClientUniqueId);
    client.setKeepAliveInterval(5 * 1000);

    BinlogFileOffset bfo = binlogFileOffset.orElse(new BinlogFileOffset("", 4));
    logger.debug("Starting with {}", bfo);
    client.setBinlogFilename(bfo.getBinlogFilename());
    client.setBinlogPosition(bfo.getOffset());

    client.setEventDeserializer(getEventDeserializer());
    client.registerEventListener(event -> {
      switch (event.getHeader().getEventType()) {
        case TABLE_MAP: {
          TableMapEventData tableMapEvent = event.getData();
          if (tableMapEvent.getTable().equalsIgnoreCase(sourceTableName)) {
            tableMapEventByTableId.put(tableMapEvent.getTableId(), tableMapEvent);
          }
          break;
        }
        case EXT_WRITE_ROWS: {
          logger.debug("Got binlog event {}", event);
          offset = ((EventHeaderV4) event.getHeader()).getPosition();
          WriteRowsEventData eventData = event.getData();
          if (tableMapEventByTableId.containsKey(eventData.getTableId())) {
            try {
              eventConsumer.accept(writeRowsEventDataParser.parseEventData(eventData,
                      getCurrentBinlogFilename(), offset
                      )
              );
            } catch (IOException e) {
              throw new RuntimeException("Event row parsing exception", e);
            }
          }
          break;
        }
        case ROTATE: {
          RotateEventData eventData = event.getData();
          if (eventData != null) {
            binlogFilename = eventData.getBinlogFilename();
          }
          break;
        }
      }
    });

    for (int i = 1;; i++) {
      try {
        logger.debug("trying to connect to mysql binlog");
        client.connect(connectionTimeoutInMilliseconds);
        logger.debug("connection to mysql binlog succeed");
        break;
      } catch (TimeoutException | IOException e) {
        logger.debug("connection to mysql binlog failed");
        if (i == maxAttemptsForBinlogConnection) {
          logger.debug("connection attempts exceeded");
          throw new RuntimeException(e);
        }
        try {
          Thread.sleep(connectionTimeoutInMilliseconds);
        } catch (InterruptedException ex) {
          throw new RuntimeException(ex);
        }
      }
    }
  }

  private EventDeserializer getEventDeserializer() {
    EventDeserializer eventDeserializer = new EventDeserializer();

    // do not deserialize binlog events except the EXT_WRITE_ROWS and TABLE_MAP
    Arrays.stream(EventType.values()).forEach(eventType -> {
      if (eventType != EventType.EXT_WRITE_ROWS &&
              eventType != EventType.TABLE_MAP &&
              eventType != EventType.ROTATE) {
        eventDeserializer.setEventDataDeserializer(eventType,
                new NullEventDataDeserializer());
      }
    });

    eventDeserializer.setEventDataDeserializer(EventType.EXT_WRITE_ROWS,
            new WriteRowsEventDataDeserializer(
                    tableMapEventByTableId).setMayContainExtraInformation(true));
    return eventDeserializer;
  }

  public void stop() {
    try {
      client.disconnect();
    } catch (IOException e) {
      logger.error("Cannot stop the MySqlBinaryLogClient", e);
    }
  }

  public String getCurrentBinlogFilename() {
    return this.binlogFilename;
  }

  public long getCurrentOffset() {
    return this.offset;
  }

  public String getName() {
    return name;
  }

}
