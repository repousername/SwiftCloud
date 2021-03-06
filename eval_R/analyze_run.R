#Import and install required packages
require("ggplot2");
require("reshape2");
require("gridExtra");
require("stringr")

# That should include at least one pruning point (60s)
PRUNE_START_MS <- 570000
DURATION_RUN_MS <- 100000
FORMAT_EXT <- ".png"
SCATTER_PLOTS_MAX_SAMPLES <- 20000
COLUMNS <- 14

load_log_files <- function(files, selector, selector_name, filtered = FALSE, substract_timestamp = 0) {
  result <- data.frame()
  total_entries <- 0
  for (file in files) {
    file_data <- read.table(file, comment.char = "#", fill = TRUE, sep = ",", stringsAsFactors=FALSE, col.names=paste("V", 1:COLUMNS, sep=""))
    total_entries <- total_entries + nrow(file_data)
    # select and name interesting columns
    file_data <- selector(file_data)
    # compute relative timestamps
    if (substract_timestamp != 0) {
      file_data <- transform(file_data, timestamp=(file_data$timestamp - substract_timestamp))
    }
    # (optional) filter by duration
    if (filtered) {
      file_data <- subset(file_data, file_data$timestamp > PRUNE_START_MS & file_data$timestamp - PRUNE_START_MS < DURATION_RUN_MS)
    }
    result <- rbind(result, file_data)
    rm(file_data)
    gc()
  }
  if (filtered) {
    type_desc <- "filtered"
  } else { 
    type_desc <- "raw"
  }
  print(paste("Loaded", nrow(result), type_desc, selector_name, "entries from total of", total_entries, "entries in", length(files), "log files"))
  return (result)
}

select_min_timestamp <- function (log) {
  return (data.frame(min_timestamp=min(log$V1)))
}

select_OP <- function (log) {
  result <- subset(log,log$V2=="APP_OP")
  result <- result[, c("V1", "V3", "V4", "V5")]
  names(result) <- c("timestamp","sessionId","operation","duration")
  result <- transform(result, sessionId=factor(sessionId), operation=factor(operation), duration = as.numeric(duration))
  return (result)
}

select_OP_FAILURE <- function (log) {
  result <- subset(log,log$V2=="APP_OP_FAILURE")
  result <- result[, c("V1", "V3", "V4", "V5")]
  names(result) <- c("timestamp","sessionId","operation","cause")
  result <- transform(result, sessionId=factor(sessionId), operation=factor(operation), cause = factor(cause))
  return (result)
}

select_OP_FAILURE_timeout_as_20000 <- function (log) {
  return (select_OP_FAILURE_by_cause_as_OP(log, "network_failure", 20000))
}

select_OP_FAILURE_pruned_as_20000 <- function (log) {
  return (select_OP_FAILURE_by_cause_as_OP(log, "network_failure", 20000))
}

select_OP_FAILURE_by_cause_as_OP <- function (log, cause, fake_duration) {
  result <- subset(log,log$V2=="APP_OP_FAILURE" & log$V5 == cause)
  result <- result[, c("V1", "V3", "V4")]
  names(result) <- c("timestamp","sessionId","operation")
  result <- transform(result, sessionId=factor(sessionId), operation=factor(operation))
  result$duration <- rep(fake_duration, nrow(result))
  return (result)
}

select_METADATA <- function (log) {
  result <- subset(log,log$V2=="METADATA")
  result <- result[, c("V1","V3", "V4", "V5",
                       #"V6", "V7",
                       "V8", "V9", "V10", "V11", "V12",
                       "V13", "V14"
                       )]
  names(result) <- c("timestamp","sessionId","message","totalMessageSize",
                   #"versionOrUpdateSize","valueSize",
                   "batchIndependentGlobalMetadata", "batchDependentGlobalMetadata",
                   "batchSizeFinestGrained", "batchSizeFinerGrained", "batchSizeCoarseGrained",
                   "maxVVSize","maxVVExceptionsNum"
                   )
  result <- transform(result, sessionId = factor(sessionId),
                      message=factor(message),
                      totalMessageSize=as.numeric(totalMessageSize),
                      #V6=as.numeric(V6), V7=as.numeric(V7),
                      batchIndependentGlobalMetadata=as.numeric(batchIndependentGlobalMetadata),
                      batchDependentGlobalMetadata=as.numeric(batchDependentGlobalMetadata),
                      batchSizeFinestGrained=as.numeric(batchSizeFinestGrained),
                      batchSizeFinerGrained=as.numeric(batchSizeFinerGrained),
                      batchSizeCoarseGrained=as.numeric(batchSizeCoarseGrained)
                      #, V12=as.numeric(V12), V13=as.numeric(V13),
                     )
  result$normalizedTotalMessageSizeByBatchSizeFinestGrained <- result$totalMessageSize / ifelse(result$batchSizeFinestGrained == 0, 1,result$batchSizeFinestGrained)
  result$normalizedTotalMessageSizeByBatchSizeFinerGrained <- result$totalMessageSize / ifelse(result$batchSizeFinerGrained == 0, 1,result$batchSizeFinerGrained)
  result$normalizedTotalMessageSizeByBatchSizeCoarseGrained <- result$totalMessageSize / ifelse(result$batchSizeCoarseGrained == 0, 1,result$batchSizeCoarseGrained)
  result$normalizedBatchDependentGlobalMetadataByBatchSizeFinestGrained <- ifelse(result$batchSizeFinestGrained == 0, 0, result$batchDependentGlobalMetadata / result$batchSizeFinestGrained)
  result$normalizedBatchDependentGlobalMetadataByBatchSizeFinerGrained <- ifelse(result$batchSizeFinerGrained == 0, 0, result$batchDependentGlobalMetadata / result$batchSizeFinerGrained)
  result$normalizedBatchDependentGlobalMetadataByBatchSizeCoarseGrained <- ifelse(result$batchSizeCoarseGrained == 0, 0, result$batchDependentGlobalMetadata / result$batchSizeCoarseGrained)
  result$totalGlobalMetadata <- result$batchIndependentGlobalMetadata + result$batchDependentGlobalMetadata
  result$normalizedTotalGlobalMetadataByBatchSizeFinestGrained <- result$totalGlobalMetadata / ifelse(result$batchSizeFinestGrained == 0, 1,result$batchSizeFinestGrained)
  result$normalizedTotalGlobalMetadataByBatchSizeFinerGrained <- result$totalGlobalMetadata / ifelse(result$batchSizeFinerGrained == 0, 1,result$batchSizeFinerGrained)
  result$normalizedTotalGlobalMetadataByBatchSizeCoarseGrained <- result$totalGlobalMetadata / ifelse(result$batchSizeCoarseGrained == 0, 1,result$batchSizeCoarseGrained)
  return (result)
}

select_DATABASE_TABLE_SIZE <- function (log) {
  # Ignore "e" dummy table.
  result <- subset(log,log$V2=="DATABASE_TABLE_SIZE" & log$V4 != "e")
  result <- result[, c("V1", "V3", "V4", "V5")]
  names(result) <- c("timestamp","nodeId","tableName","tableSize")
  result <- transform(result, nodeId=factor(nodeId), tableName=factor(tableName), tableSize=as.numeric(tableSize))
  return (result)
}

select_and_extrapolate_IDEMPOTENCE_GUARD_SIZE <- function (log) {
  max_timestamp <- max(log$V1)
  result <- subset(log,log$V2=="IDEMPOTENCE_GUARD_SIZE")
  result <- result[, c("V1", "V3", "V4")]
  names(result) <- c("timestamp","nodeId","idempotenceGuardSize")
  result <- transform(result, nodeId=factor(nodeId), idempotenceGuardSize=as.numeric(idempotenceGuardSize))
  for (dc in unique(result$nodeId)) {
    dc_last_guard <- tail(subset(result, result$nodeId == dc), 1)
    MIN_SAMPLING_PERIOD <- 1000
    if (dc_last_guard$timestamp + MIN_SAMPLING_PERIOD < max_timestamp) {
      missing_timestamps <- seq(dc_last_guard$timestamp + MIN_SAMPLING_PERIOD, max_timestamp, by=MIN_SAMPLING_PERIOD)
      extrapolated_entries <- data.frame(timestamp=missing_timestamps,
                                       nodeId=as.character(rep(dc, length(missing_timestamps))),
                                       idempotenceGuardSize=rep(dc_last_guard$idempotenceGuardSize, length(missing_timestamps)))
      result <- rbind(result, extrapolated_entries)
    }
  }
  return (result)
}

extract_objid_from_STALENESS_YCSB_key <- function (key) {
  match <- str_match(key, "([^:]+:[^:]+):.+")
  
  if (length(match) < 2 || is.na(match[2])) {
    warning("cannot extract key id from object id", key)
    return (key)
  }
  return (match[2])
}

select_object_accesses_from_STALENESS_YCSB_WRITE <- function (log) {
  return (select_object_accesses_from_STALENESS(log, "STALENESS_YCSB_WRITE", extract_objid_from_STALENESS_YCSB_key))
}

select_object_accesses_from_STALENESS_YCSB_READ <- function (log) {
  return (select_object_accesses_from_STALENESS(log, "STALENESS_YCSB_READ", extract_objid_from_STALENESS_YCSB_key))
}

select_object_accesses_from_STALENESS_READ <- function (log) {
  return (select_object_accesses_from_STALENESS(log, "STALENESS_READ", identity))
}

select_object_accesses_from_STALENESS_WRITE <- function (log) {
  return (select_object_accesses_from_STALENESS(log, "STALENESS_WRITE", identity))
}

select_object_accesses_from_STALENESS <- function (log, entry_type, id_extractor) {
  max_timestamp <- max(log$V1)
  result <- subset(log,log$V2==entry_type)
  result <- result[, c("V1", "V4")]
  names(result) <- c("timestamp","objectId")
  # TODO
  result$objectId <- sapply(result$objectId, id_extractor)
  return (result)
}

sample_entries <- function(entries) {
  if (nrow(entries) > SCATTER_PLOTS_MAX_SAMPLES) {
    return (entries[sample(nrow(entries), SCATTER_PLOTS_MAX_SAMPLES), ])
  }
  return (entries)
}

process_experiment_run_dir <- function(dir, output_prefix, spectrogram=TRUE,summarized=TRUE) {
  # Create destination directory
  dir.create(dirname(output_prefix), recursive=TRUE, showWarnings=FALSE)

  # Find scout logs
  client_file_list <- list.files(dir, pattern="*scout-stdout.log",recursive=TRUE,full.names=TRUE)
  if (length(client_file_list) == 0) {
    warning(paste("No scout logs found in", dir))
  }
  
  # Find DC logs
  dc_file_list <- list.files(dir, pattern="*sur-stdout.log",recursive=TRUE,full.names=TRUE)
  if (length(dc_file_list) == 0) {
    warning(paste("No DC logs found in", dir))
  }
  
  dmins <- load_log_files(client_file_list, select_min_timestamp, "min. timestamp", FALSE)
  min_timestamp <- min(dmins$min_timestamp)
  rm(dmins)

  # "SUMMARIZED" MODE OUTPUT
  if (summarized) {
    # Errors descriptive statistics
    derr <- load_log_files(client_file_list, select_OP_FAILURE, "OP_FAILURE", FALSE, min_timestamp)
    errors.stats <- data.frame(cause=character(),occurences=integer())  
    # TODO: do it in R-idiomatic way
    for (c in unique(derr$cause)) {
      o <- nrow(subset(derr, derr$cause==c))
      errors.stats <- rbind(errors.stats, data.frame(cause=c, occurences=o))
    }
    rm(derr)
    
    # common output format for descriptive statistics
    quantile_steps <- seq(from=0.0, to=1.0, by=0.001)
    stats <- c("mean", "stddev", rep("permille", length(quantile_steps)))
    stats_params <- c(0, 0, quantile_steps)
    compute_stats <- function(vec) {
      return (c(mean(vec), sd(vec), quantile(vec, probs=quantile_steps)))
    }

    dop_filtered <- load_log_files(client_file_list, select_OP, "OP", TRUE, min_timestamp)
    #dop_timeout_filtered <- load_log_files(client_file_list, select_OP_FAILURE_timeout_as_20000, "OP_FAILURE", TRUE, min_timestamp)
    #dop_pruned_filtered <- load_log_files(client_file_list, select_OP_FAILURE_pruned_as_20000, "OP_FAILURE", TRUE, min_timestamp)
    #dop_with_timeout_filtered <- rbind(dop_filtered, dop_timeout_filtered)
    #dop_with_timeout_pruned_filtered <- rbind(dop_with_timeout_filtered, dop_pruned_filtered)
    #rm(dop_timeout_filtered)
    #rm(dop_pruned_filtered)
    if (nrow(dop_filtered) > 0) {
      # Throughput over time plot
      # Careful: It seems that the first and last bin only cover 5000 ms
      throughput.plot <- ggplot(dop_filtered, aes(x=timestamp, color=sessionId)) + geom_histogram(binwidth=1000)
      throughput.plot <- throughput.plot + labs(title="Throughput colored by client  session", x="time [ms]",y = "throughput [txn/s]") + guides(fill=FALSE)
      #throughput.plot
      ggsave(throughput.plot, file=paste(output_prefix, "-throughput",FORMAT_EXT,collapse="", sep=""), scale=1)
      rm(throughput.plot)
  
      # Operation duration CDF plot for the filtered period
      cdf.plot <- ggplot(dop_filtered, aes(x=duration)) + stat_ecdf(aes(colour=operation)) # + ggtitle (paste("TH",th))
      cdf.plot <- cdf.plot + labs(x="response time [ms]",y = "CDF")
      # cdf.plot
      ggsave(cdf.plot, file=paste(output_prefix, "-cdf",FORMAT_EXT,collapse="", sep=""), scale=1)
      rm(cdf.plot)
      
      operations_stats <- data.frame(stat=stats, stat_param=stats_params)
      compute_all_stats <- function (ops, ops_name_suffix) {
        # Throughput / response time descriptive statistics over filtered data
        time_steps <- c(seq(min(ops$timestamp),max(ops$timestamp),by=1000), max(ops$timestamp))
        through <- hist(ops$timestamp, breaks=time_steps)
    
        operations_stats[[paste("throughput", ops_name_suffix, sep="")]] <<- compute_stats(through$counts)
        operations_stats[[paste("response_time", ops_name_suffix, sep="")]] <<- compute_stats(ops$duration)
        for (op in unique(ops$operation)) {
          op_filtered <- subset(ops, operation == op)
          operations_stats[[paste(paste("response_time", ops_name_suffix, sep=""), op, sep="_")]] <<- compute_stats(op_filtered$duration)
        }
        rm(through)
        rm(time_steps)
      }
      compute_all_stats(dop_filtered, "")
      # compute_all_stats(dop_with_timeout_filtered, "_with_timeout")
      # rm(dop_with_timeout_filtered)
      # rm(dop_with_timeout_pruned_filtered)
      # compute_all_stats(dop_with_timeout_pruned_filtered, "_with_timeout_pruned")
      write.table(operations_stats, paste(output_prefix, "ops.csv", sep="-"), sep=",", row.names=FALSE)
    } else {
      warning(paste("no filtered OPs found in", dir))
      errors.stats <- rbind(errors.stats, data.frame(cause="no_OPs_found", occurences=10000))
    }
    rm(dop_filtered)


    # Metadata size descriptive statistics
    dmetadata_filtered <- load_log_files(client_file_list, select_METADATA, "METADATA", TRUE,min_timestamp)
    metadata_size_stats <- data.frame(stat=stats, stat_param=stats_params)
    if (nrow(dmetadata_filtered) > 0) {
      for (m in unique(dmetadata_filtered$message)) {
        m_filtered <- subset(dmetadata_filtered, dmetadata_filtered$message==m)
        metadata_size_stats[[paste(m, "msg", sep="-")]] <- compute_stats(m_filtered$totalMessageSize)
        metadata_size_stats[[paste(m,"msg", "norm1",sep="-")]] <- compute_stats(m_filtered$normalizedTotalMessageSizeByBatchSizeFinestGrained)
        metadata_size_stats[[paste(m,"msg", "norm2",sep="-")]] <- compute_stats(m_filtered$normalizedTotalMessageSizeByBatchSizeFinerGrained)
        metadata_size_stats[[paste(m,"msg", "norm3",sep="-")]] <- compute_stats(m_filtered$normalizedTotalMessageSizeByBatchSizeCoarseGrained)
        metadata_size_stats[[paste(m, "meta-indep", sep="-")]] <- compute_stats(m_filtered$batchIndependentGlobalMetadata)
        metadata_size_stats[[paste(m, "meta-dep", sep="-")]] <- compute_stats(m_filtered$batchDependentGlobalMetadata)
        metadata_size_stats[[paste(m, "meta-tot", sep="-")]] <- compute_stats(m_filtered$totalGlobalMetadata)
        metadata_size_stats[[paste(m, "meta-dep", "norm1",sep="-")]] <- compute_stats(m_filtered$normalizedBatchDependentGlobalMetadataByBatchSizeFinestGrained)
        metadata_size_stats[[paste(m, "meta-dep", "norm2",sep="-")]] <- compute_stats(m_filtered$normalizedBatchDependentGlobalMetadataByBatchSizeFinerGrained)
        metadata_size_stats[[paste(m, "meta-dep", "norm3",sep="-")]] <- compute_stats(m_filtered$normalizedBatchDependentGlobalMetadataByBatchSizeCoarseGrained)
        metadata_size_stats[[paste(m, "meta-tot", "norm1",sep="-")]] <- compute_stats(m_filtered$normalizedTotalGlobalMetadataByBatchSizeFinestGrained)
        metadata_size_stats[[paste(m, "meta-tot", "norm2",sep="-")]] <- compute_stats(m_filtered$normalizedTotalGlobalMetadataByBatchSizeFinerGrained)
        metadata_size_stats[[paste(m, "meta-tot", "norm3",sep="-")]] <- compute_stats(m_filtered$normalizedTotalGlobalMetadataByBatchSizeCoarseGrained)
        metadata_size_stats[[paste(m, "vv-size", sep="-")]] <- compute_stats(m_filtered$maxVVSize)
        metadata_size_stats[[paste(m, "vv-exceptions", sep="-")]] <- compute_stats(m_filtered$maxVVExceptionsNum)
        metadata_size_stats[[paste(m, "batch1", sep="-")]] <- compute_stats(m_filtered$batchSizeFinestGrained)
        metadata_size_stats[[paste(m, "batch2", sep="-")]] <- compute_stats(m_filtered$batchSizeFinerGrained)
        metadata_size_stats[[paste(m, "batch3", sep="-")]] <- compute_stats(m_filtered$batchSizeCoarseGrained)
      }
    } else {
      warning(paste("no METADATA entriesfound in", dir))
      errors.stats <- rbind(errors.stats, data.frame(cause="no_METADATAs_found", occurences=10000))
    }
    rm(dmetadata_filtered)

    write.table(errors.stats, paste(output_prefix, "errors.csv", sep="-"), sep=",", row.names=FALSE)
    
    process_db_size <- function(file_list, node_type) {
      dtablesize_filtered <- load_log_files(file_list, select_DATABASE_TABLE_SIZE, "DATABASE_TABLE_SIZE", TRUE, min_timestamp)
      if (nrow(dtablesize_filtered) > 0) {
        for (eachTable in unique(dtablesize_filtered$tableName)) {
          tablestats <- subset(dtablesize_filtered, dtablesize_filtered$tableName == eachTable)
          metadata_size_stats[[paste(eachTable, "table", node_type, sep="-")]] <<- compute_stats(tablestats$tableSize)
        }
      }
      rm(dtablesize_filtered)
    }
    process_db_size(dc_file_list, "dc")
    process_db_size(client_file_list, "client")
    dguardsize_filtered <- load_log_files(dc_file_list, select_and_extrapolate_IDEMPOTENCE_GUARD_SIZE, "IDEMPOTENCE_GUARD_SIZE", TRUE, min_timestamp)
    if (nrow(dguardsize_filtered) > 0) {
      metadata_size_stats$idempotenceGuard <- compute_stats(dguardsize_filtered$idempotenceGuardSize)
    }
    rm(dguardsize_filtered)
    write.table(metadata_size_stats, paste(output_prefix, "meta_size.csv", sep="-"), sep=",", row.names=FALSE)
    rm(metadata_size_stats)
  }

  # "SPECTROGRAM" MODE OUPUT
  if (spectrogram) {
    dop_raw <- load_log_files(client_file_list, select_OP, "OP", FALSE, min_timestamp)

    # Response time scatterplot over time  
    dop_raw_sampled <- sample_entries(dop_raw)
    scatter.plot <- ggplot(dop_raw_sampled, aes(timestamp,duration)) + geom_point(aes(color=operation))
    ggsave(scatter.plot, file=paste(output_prefix, "-response_time",FORMAT_EXT,collapse="", sep=""), scale=1)
    rm(scatter.plot)
    
    # Throughput over time plot
    throughput.plot <- ggplot(dop_raw, aes(x=timestamp, color=sessionId)) + geom_histogram(binwidth=1000)
    throughput.plot <- throughput.plot + labs(title="Throughput colored by client  session", x="time [ms]",y = "throughput [txn/s]") + guides(fill=FALSE)
    #throughput.plot
    ggsave(throughput.plot, file=paste(output_prefix, "-throughput_full",FORMAT_EXT,collapse="", sep=""), scale=1)
    rm(throughput.plot)

    #Histogram
    #p <- qplot(duration, data = d,binwidth=5,color=operation,geom="freqpoly") + facet_wrap( ~ sessionId)
    rm(dop_raw)
    rm(dop_raw_sampled)

    
    dmetadata_raw <- load_log_files(client_file_list, select_METADATA, "METADATA", FALSE, min_timestamp)
    # Message occurences over time plot(s)
    for (m in unique(dmetadata_raw$message)) {
      m_metadata <- subset(dmetadata_raw, dmetadata_raw$message==m) 
      msg.plot <- ggplot(m_metadata, aes(x=timestamp, color=sessionId)) + geom_histogram(binwidth=1000) 
      msg.plot <- msg.plot + labs(title=paste(m, "message occurence, colored by client session"), x="time [ms]",y = "#occurrences / s") + guides(fill=FALSE)
      # msg.plot
      ggsave(msg.plot, file=paste(output_prefix, "-msg_occur-", m, FORMAT_EXT,collapse="", sep=""), scale=1)
      rm(msg.plot)
    }
    dmetadata_raw_sampled <- sample_entries(dmetadata_raw)
    # Message size and metadata size over time scatter plots
    msg_size.plot <- ggplot(dmetadata_raw_sampled, aes(timestamp,totalMessageSize)) + geom_point(aes(color=message))
    ggsave(msg_size.plot, file=paste(output_prefix, "-msg_size",FORMAT_EXT,collapse="", sep=""), scale=1)
    rm(msg_size.plot)

    metadata_size.plot <- ggplot(dmetadata_raw_sampled, aes(timestamp,batchIndependentGlobalMetadata)) + geom_point(aes(color=message))
    ggsave(metadata_size.plot, file=paste(output_prefix, "-msg_meta_indep",FORMAT_EXT,collapse="", sep=""), scale=1)
    rm(metadata_size.plot)
    
    metadata_size.plot <- ggplot(dmetadata_raw_sampled, aes(timestamp,totalGlobalMetadata)) + geom_point(aes(color=message))
    ggsave(metadata_size.plot, file=paste(output_prefix, "-msg_meta_tot",FORMAT_EXT,collapse="", sep=""), scale=1)
    rm(metadata_size.plot)
    
    metadata_size.plot <- ggplot(dmetadata_raw_sampled, aes(timestamp,batchDependentGlobalMetadata)) + geom_point(aes(color=message))
    ggsave(metadata_size.plot, file=paste(output_prefix, "-msg_meta_dep",FORMAT_EXT,collapse="", sep=""), scale=1)
    rm(metadata_size.plot)

    metadata_norm1_size.plot <- ggplot(dmetadata_raw_sampled, aes(timestamp, normalizedTotalGlobalMetadataByBatchSizeFinestGrained)) + geom_point(aes(color=message))
    ggsave(metadata_norm1_size.plot, file=paste(output_prefix, "-msg_meta_tot_norm1",FORMAT_EXT,collapse="", sep=""), scale=1)
    rm(metadata_norm1_size.plot)

    metadata_norm2_size.plot <- ggplot(dmetadata_raw_sampled, aes(timestamp, normalizedTotalGlobalMetadataByBatchSizeFinerGrained)) + geom_point(aes(color=message))
    ggsave(metadata_norm2_size.plot, file=paste(output_prefix, "-msg_meta_tot_norm2",FORMAT_EXT,collapse="", sep=""), scale=1)
    rm(metadata_norm2_size.plot)

    metadata_norm3_size.plot <- ggplot(dmetadata_raw_sampled, aes(timestamp, normalizedTotalGlobalMetadataByBatchSizeCoarseGrained)) + geom_point(aes(color=message))
    ggsave(metadata_norm3_size.plot, file=paste(output_prefix, "-msg_meta_tot_norm3",FORMAT_EXT,collapse="", sep=""), scale=1)
    rm(metadata_norm3_size.plot)
    
    metadata_norm1_size.plot <- ggplot(dmetadata_raw_sampled, aes(timestamp, normalizedBatchDependentGlobalMetadataByBatchSizeFinestGrained)) + geom_point(aes(color=message))
    ggsave(metadata_norm1_size.plot, file=paste(output_prefix, "-msg_meta_dep_norm1",FORMAT_EXT,collapse="", sep=""), scale=1)
    rm(metadata_norm1_size.plot)
    
    metadata_norm2_size.plot <- ggplot(dmetadata_raw_sampled, aes(timestamp, normalizedBatchDependentGlobalMetadataByBatchSizeFinerGrained)) + geom_point(aes(color=message))
    ggsave(metadata_norm2_size.plot, file=paste(output_prefix, "-msg_meta_dep_norm2",FORMAT_EXT,collapse="", sep=""), scale=1)
    rm(metadata_norm2_size.plot)
    
    metadata_norm3_size.plot <- ggplot(dmetadata_raw_sampled, aes(timestamp, normalizedBatchDependentGlobalMetadataByBatchSizeCoarseGrained)) + geom_point(aes(color=message))
    ggsave(metadata_norm3_size.plot, file=paste(output_prefix, "-msg_meta_dep_norm3",FORMAT_EXT,collapse="", sep=""), scale=1)
    rm(metadata_norm3_size.plot)
    
    metadata_batch1_size.plot <- ggplot(dmetadata_raw_sampled, aes(timestamp, batchSizeFinestGrained)) + geom_point(aes(color=message))
    ggsave(metadata_batch1_size.plot, file=paste(output_prefix, "-batch_size1",FORMAT_EXT,collapse="", sep=""), scale=1)
    rm(metadata_batch1_size.plot)
    
    metadata_batch2_size.plot <- ggplot(dmetadata_raw_sampled, aes(timestamp, batchSizeFinerGrained)) + geom_point(aes(color=message))
    ggsave(metadata_batch2_size.plot, file=paste(output_prefix, "-batch_size2",FORMAT_EXT,collapse="", sep=""), scale=1)
    rm(metadata_batch2_size.plot)
    
    metadata_batch3_size.plot <- ggplot(dmetadata_raw_sampled, aes(timestamp, batchSizeCoarseGrained)) + geom_point(aes(color=message))
    ggsave(metadata_batch3_size.plot, file=paste(output_prefix, "-batch_size3",FORMAT_EXT,collapse="", sep=""), scale=1)
    rm(metadata_batch3_size.plot)
    
    rm(dmetadata_raw)
    rm(dmetadata_raw_sampled)


    process_db_size_raw <- function(file_list, node_type) {
      dtablesize_raw <- load_log_files(file_list, select_DATABASE_TABLE_SIZE, "DATABASE_TABLE_SIZE", FALSE, min_timestamp)
      if (nrow(dtablesize_raw) > 0) {
        # Storage metadata/db size, plots over time
        for (tab in unique(dtablesize_raw$tableName)) {
          db_table_size.plot <- ggplot()
          tabsize <- subset(dtablesize_raw, tableName == tab)
          for (eachNode in unique(tabsize$nodeId)) {
            dctablesize <- subset(tabsize, nodeId == eachNode)
            dctablesize$dcName <- paste(node_type, dctablesize$nodeId)
            db_table_size.plot <- db_table_size.plot + geom_line(data=dctablesize, mapping=aes(timestamp,tableSize, color=dcName))
          }
          db_table_size.plot <- db_table_size.plot + labs(title=paste(tab, "table size"), x="time [ms]",y = "size [bytes]")
          # TODO title: ", table", dctablesize$tableName
          ggsave(db_table_size.plot, file=paste(output_prefix, "-tab_size-", tolower(node_type), "-", tab, FORMAT_EXT,collapse="", sep=""), scale=1)
          rm(db_table_size.plot)
        }
      }
      rm(dtablesize_raw)  
    }
    process_db_size_raw(dc_file_list, "DC")
    process_db_size_raw(client_file_list, "client")
    

    dguardsize_raw <- load_log_files(dc_file_list, select_and_extrapolate_IDEMPOTENCE_GUARD_SIZE, "IDEMPOTENCE_GUARD_SIZE", FALSE, min_timestamp)
    if (nrow(dguardsize_raw) > 0) {
      guard_size.plot <- ggplot()
      for (eachDc in unique(dguardsize_raw$nodeId)) {
        dcguardsize <- subset(dguardsize_raw, dguardsize_raw$nodeId == eachDc)
        guard_size.plot <- guard_size.plot + geom_line(data=dcguardsize, mapping=aes(timestamp,idempotenceGuardSize,color=nodeId))
      }
  
      ggsave(guard_size.plot, file=paste(output_prefix, "-guard_size",FORMAT_EXT,collapse="", sep=""), scale=1)
      rm(guard_size.plot)
    }
    rm(dguardsize_raw)

    process_requests_from_staleness <- function(type_str, selector) {
      drequests_raw <- load_log_files(client_file_list, selector, paste("ACCESSED OBJECT IDs (staleness", type_str, "entries)"), FALSE, min_timestamp)
      if (nrow(drequests_raw) > 0) {
        requested_objects_num <- length(unique(drequests_raw$objectId))
        drequests_raw <- transform(drequests_raw,
                               objectId = factor(objectId, levels = names(sort(-table(objectId))), labels = 1:requested_objects_num))
        writes_distr.plot <- ggplot(drequests_raw, aes(x=objectId)) + geom_histogram(bin_width=1)
        writes_distr.plot <- writes_distr.plot + labs(title = paste("object", type_str, "distribution"), x="object rank",y = paste("# field", type_str))
        writes_distr.plot <- writes_distr.plot + scale_x_discrete(breaks = seq(1, requested_objects_num, by = max(1, round(requested_objects_num/ 15))))
        ggsave(writes_distr.plot, file=paste(output_prefix, "-obj_", type_str, "_distr", FORMAT_EXT,collapse="", sep=""), scale=1)
        rm(writes_distr.plot)
        rm(drequests_raw)
      }
    }
    process_requests_from_staleness("writes", select_object_accesses_from_STALENESS_YCSB_WRITE)
    process_requests_from_staleness("reads", select_object_accesses_from_STALENESS_YCSB_READ)
    process_requests_from_staleness("writes", select_object_accesses_from_STALENESS_WRITE)
    process_requests_from_staleness("reads", select_object_accesses_from_STALENESS_READ)
  }
}

process_experiment_run <- function(path, spectrogram=TRUE, summarized=TRUE, output_dir=file.path(dirname(path), "processed")) {
  if (file.info(path)$isdir) {
    # prefix <- sub(paste(.Platform$file.sep, "$", sep=""),  "", path)
    output_prefix <- file.path(output_dir, basename(path))
    process_experiment_run_dir(dir=path, output_prefix=output_prefix, spectrogram, summarized)
  } else {
    # presume it is a tar.gz archive
    run_id <- sub(".tar.gz", "", basename(path))
    tmp_dir <- tempfile(pattern=run_id)
    output_prefix <- file.path(output_dir, run_id)
    untar_code <- untar(path, exdir=tmp_dir, compressed="gzip")
    if (untar_code != 0) {
      write.table(data.frame(cause="untar_failed", occurences=10000), paste(output_prefix, "errors.csv", sep="-"), sep=",", row.names=FALSE)
      unlink(tmp_dir, recursive=TRUE)
      stop(paste("untar of", path, "failed"))
    }
    process_experiment_run_dir(dir=tmp_dir,  output_prefix=output_prefix, spectrogram, summarized)
    unlink(tmp_dir, recursive=TRUE)
  }
}

fail_usage <- function() {
  stop("syntax: analyze_run.R <all|spectrogram|summarized> <directory or tar.gz archive with log files for a run> [output directory]")
}

if (length(commandArgs(TRUE)) >= 2) {
  spectrogram <- TRUE
  summarized <- TRUE
  mode <- commandArgs(TRUE)[1]
  if (mode == "all") {
    # NOP
  } else if (mode == "spectrogram") {
    summarized <- FALSE
  } else if (mode == "summarized") {
    spectrogram <- FALSE
  } else {
    fail_usage()
  }
  path <- normalizePath(commandArgs(TRUE)[2])
  if (length(commandArgs(TRUE)) >= 3) {
    output_dir <- commandArgs(TRUE)[3]
    process_experiment_run(path, spectrogram, summarized, output_dir)
  } else {
    process_experiment_run(path, spectrogram, summarized)
  }
} else {
  if (interactive()) {
    print("INTERACTIVE MODE: use process_experiment_run() function")
  } else {
    fail_usage()
  }
}

