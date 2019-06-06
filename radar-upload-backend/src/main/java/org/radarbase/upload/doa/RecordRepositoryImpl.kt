package org.radarbase.upload.doa

import org.hibernate.Hibernate
import org.hibernate.Session
import org.radarbase.upload.api.RecordMetadataDTO
import org.radarbase.upload.doa.entity.*
import org.radarbase.upload.exception.ConflictException
import org.radarbase.upload.inject.session
import org.radarbase.upload.inject.transact
import java.io.InputStream
import java.sql.Blob
import java.time.Instant
import javax.persistence.EntityManager
import javax.ws.rs.BadRequestException
import javax.ws.rs.NotFoundException
import javax.ws.rs.core.Context
import kotlin.streams.toList


class RecordRepositoryImpl(@Context private var em: EntityManager) : RecordRepository {

    override fun updateLogs(id: Long, logsData: String): RecordMetadata = em.transact {
        val logs = find(RecordLogs::class.java, id)
        val metadataToSave = if (logs == null) {
            val metadataFromDb = find(RecordMetadata::class.java, id)
                    ?: throw NotFoundException("RecordMetadata with ID $id does not exist")
            refresh(metadataFromDb)
            metadataFromDb.logs = RecordLogs().apply {
                this.metadata = metadataFromDb
                this.modifiedDate = Instant.now()
                this.size = logsData.length.toLong()
                this.logs = Hibernate.getLobCreator(em.session).createClob(logsData)
            }.also {
                persist(it)
            }
            metadataFromDb
        } else {
            logs.apply {
                this.modifiedDate = Instant.now()
                this.logs = Hibernate.getLobCreator(em.session).createClob(logsData)
            }
            merge(logs)
            logs.metadata
        }

        modifyNow(metadataToSave)
    }

    override fun query(limit: Int, lastId: Long, projectId: String, userId: String?, status: String?): List<Record> {
        var queryString = "SELECT r FROM Record r WHERE r.projectId = :projectId AND r.id > :lastId"
        userId?.let {
            queryString += " AND r.userId = :userId"
        }
        status?.let {
            queryString += " AND r.metadata.status = :status"
        }
        queryString += " ORDER BY r.id"

        return em.transact {
            val query = createQuery(queryString, Record::class.java)
                    .setParameter("lastId", lastId)
                    .setParameter("projectId", projectId)
                    .setMaxResults(limit)
            userId?.let {
                query.setParameter("userId", it)
            }
            status?.let {
                query.setParameter("status", it)
            }
            query.resultList
        }
    }

    override fun readLogs(id: Long): RecordLogs? = em.transact {
        find(RecordLogs::class.java, id)
    }

    override fun updateContent(record: Record, fileName: String, contentType: String, stream: InputStream, length: Long): RecordContent = em.transact {
        val existingContent = record.contents?.find { it.fileName == fileName }

        val result = existingContent?.apply {
            this.createdDate = Instant.now()
            this.contentType = contentType
            this.content = Hibernate.getLobCreator(em.unwrap(Session::class.java)).createBlob(stream, length)
        } ?: RecordContent().apply {
            this.record = record
            this.fileName = fileName
            this.createdDate = Instant.now()
            this.contentType = contentType
            this.size = length
            this.content = Hibernate.getLobCreator(em.unwrap(Session::class.java)).createBlob(stream, length)
        }.also {
            if (record.contents != null) {
                record.contents?.add(it)
            } else {
                record.contents = mutableSetOf(it)
            }
        }

        record.metadata.apply {
            status = RecordStatus.READY
            message = "Data successfully uploaded, ready for processing."
            update()
        }

        merge(record)
        return@transact result
    }

    override fun poll(limit: Int): List<Record> = em.transact {
        createQuery("SELECT r FROM Record r WHERE r.metadata.status = :status ORDER BY r.metadata.modifiedDate", Record::class.java)
                .setParameter("status", RecordStatus.valueOf("READY"))
                .setMaxResults(limit)
                .resultStream
                .peek {
                    it.metadata.status = RecordStatus.QUEUED
                    it.metadata.message = "Record is queued for processing"
                    modifyNow(it.metadata)
                }
                .toList()
    }

    override fun readRecordContent(recordId: Long, fileName: String): RecordContent? = em.transact {
        val queryString = "SELECT rc from RecordContent rc WHERE rc.record.id = :id AND rc.fileName = :fileName"

        createQuery(queryString, RecordContent::class.java)
                .setParameter("fileName", fileName)
                .setParameter("id", recordId)
                .resultList.firstOrNull()
    }

    override fun readFileContent(id: Long, fileName: String): ByteArray? = em.transact {
        val queryString = "SELECT rc.content from RecordContent rc WHERE rc.record.id = :id AND rc.fileName = :fileName"

        createQuery(queryString, Blob::class.java)
                .setParameter("fileName", fileName)
                .setParameter("id", id)
                .resultList.firstOrNull()?.binaryStream?.readAllBytes()
    }

    override fun create(record: Record): Record = em.transact {
        val tmpContent = record.contents?.also { record.contents = null }

        persist(record)

        record.apply {
            contents = tmpContent?.mapTo(HashSet()) {
                it.record = record
                it.createdDate = Instant.now()
                persist(it)
                it
            }

            metadata = RecordMetadata().apply {
                this.record = record
                status = if (tmpContent == null) RecordStatus.INCOMPLETE else RecordStatus.READY
                message = if (tmpContent == null) "No data uploaded yet" else "Data successfully uploaded, ready for processing."
                createdDate = Instant.now()
                modifiedDate = Instant.now()
                revision = 1
                persist(this)
            }
        }
    }

    override fun read(id: Long): Record? = em.transact { find(Record::class.java, id) }

    override fun update(record: Record): Record = em.transact {
        record.metadata.update()
        merge<Record>(record)
    }

    private fun RecordMetadata.update() {
        this.revision += 1
        this.modifiedDate = Instant.now()
    }

    private fun modifyNow(metadata: RecordMetadata): RecordMetadata {
        metadata.update()
        return em.merge<RecordMetadata>(metadata)
    }

    override fun updateMetadata(id: Long, metadata: RecordMetadataDTO): RecordMetadata = em.transact {
        val existingMetadata = find(RecordMetadata::class.java, id)
                ?: throw NotFoundException("RecordMetadata with ID $id does not exist")

        if (existingMetadata.revision != metadata.revision)
            throw BadRequestException("Requested meta data revision ${metadata.revision} " +
                    "should match the latest existing revision ${existingMetadata.revision}")

        if (metadata.status == RecordStatus.PROCESSING.toString()
                && existingMetadata.status != RecordStatus.QUEUED) {
            throw ConflictException("Record cannot be updated: Conflict in record meta-data status. " +
                    "Found ${existingMetadata.status}, expected ${RecordStatus.QUEUED}")
        }

        if (metadata.status == RecordStatus.SUCCEEDED.toString()
                || metadata.status == RecordStatus.FAILED.toString()
                && existingMetadata.status != RecordStatus.PROCESSING) {
            throw ConflictException("Record cannot be updated: Conflict in record meta-data status. " +
                    "Found ${existingMetadata.status}, expected ${RecordStatus.QUEUED}")
        }

        existingMetadata.apply {
            status = RecordStatus.valueOf(metadata.status)
            message = metadata.message
        }

        modifyNow(existingMetadata)
    }

    override fun delete(record: Record) = em.transact { remove(record) }

    override fun readMetadata(id: Long): RecordMetadata? = em.transact { find(RecordMetadata::class.java, id) }
}
