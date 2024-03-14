package io.shortcut.firestoreextensions

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.firestore.AggregateQuery
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.transformWhile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


suspend fun CollectionReference.fetch(source: Source = Source.DEFAULT) =
    suspendCoroutine<QuerySnapshot> { c ->
        get(source).addOnSuccessListener { collection -> c.resume(collection) }
            .addOnFailureListener { e -> c.resumeWithException(e) }
    }

/**
 * Fetch the querySnapshot from cache ignoring exceptions.
 */
private suspend fun CollectionReference.fetchCacheOrNull() = suspendCoroutine<QuerySnapshot?> { c ->
    this.get(Source.CACHE).addOnCompleteListener { task ->
        c.resume(
            if (task.isSuccessful) task.result else null
        )
    }
}

/**
 * Returns a flow that stops after receiving a value from the server.
 * Use this to read from cache and wait for a value from the server.
 */
private fun DocumentReference.flowOnce(): Flow<DocumentSnapshot?> =
    asFlow().transformWhile { snapshot ->
        emit(snapshot)
        snapshot.metadata.isFromCache
    }


suspend fun Query.fetch(source: Source = Source.DEFAULT) = suspendCoroutine<QuerySnapshot> { c ->
    get(source).addOnSuccessListener { snapshot -> c.resume(snapshot) }
        .addOnFailureListener { e -> c.resumeWithException(e) }
}

private suspend fun Query.fetchOrNull(source: Source = Source.DEFAULT) =
    suspendCoroutine<QuerySnapshot?> { c ->
        this.get(source).addOnCompleteListener { task ->
            c.resume(
                if (task.isSuccessful) task.result else null
            )
        }
    }

/**
 * Fetch the querySnapshot ignoring exceptions.
 */
private suspend fun Query.fetchCacheOrNull() = suspendCoroutine<QuerySnapshot?> { c ->
    this.get(Source.CACHE).addOnCompleteListener { task ->
        c.resume(
            if (task.isSuccessful) task.result else null
        )
    }
}

suspend fun DocumentReference.fetch(source: Source = Source.DEFAULT) =
    suspendCoroutine<DocumentSnapshot> { c ->
        get(source).addOnSuccessListener { snapshot -> c.resume(snapshot) }
            .addOnFailureListener { e -> c.resumeWithException(e) }
    }

/**
 * Fetch the DocumentSnapshot ignoring exceptions.
 */
suspend fun DocumentReference.fetchOrNull(source: Source = Source.DEFAULT) =
    suspendCoroutine<DocumentSnapshot?> { c ->
        this.get(source).addOnCompleteListener { task ->
            c.resume(
                if (task.isSuccessful) task.result else null
            )
        }
    }

/**
 * Fetch the DocumentSnapshot from cache ignoring exceptions.
 */
private suspend fun DocumentReference.fetchCacheOrNull() =
    suspendCoroutine<DocumentSnapshot?> { c ->
        this.get(Source.CACHE).addOnCompleteListener { task ->
            c.resume(
                if (task.isSuccessful) task.result else null
            )
        }
    }

/**
 * Fetch an AggregateQuery result from the server ignoring exceptions.
 */
suspend fun AggregateQuery.fetchOrNull() = suspendCoroutine<Long?> { c ->
    this.get(AggregateSource.SERVER)
        .addOnSuccessListener { snapshot -> c.resume(snapshot.count) }
        .addOnFailureListener { c.resume(null) }
}

/**
 * Attch a snapshotListener to a query, converted to a flow.
 */
fun Query.asFlow(): Flow<QuerySnapshot> {
    return callbackFlow {
        val callback = addSnapshotListener { querySnapshot, firebaseFirestoreException ->
            if (firebaseFirestoreException != null) {
                if (firebaseFirestoreException.code ==
                    FirebaseFirestoreException.Code.PERMISSION_DENIED &&
                    querySnapshot == null
                ) {
                    // Swallow this exception as it causes a crash when signing out
                    return@addSnapshotListener
                }

                close(firebaseFirestoreException)
            } else {
                trySend(querySnapshot!!)
            }
        }
        awaitClose {
            callback.remove()
        }
    }
}

/**
 * Attach a snapshotListener to a documentReference, converted to a flow.
 */
fun DocumentReference.asFlow(): Flow<DocumentSnapshot> {
    return callbackFlow {
        val callback = addSnapshotListener { querySnapshot, firebaseFirestoreException ->
            if (firebaseFirestoreException != null) {
                if (firebaseFirestoreException.code ==
                    FirebaseFirestoreException.Code.PERMISSION_DENIED &&
                    querySnapshot == null
                ) {
                    // Swallow this exception as it causes a crash when signing out
                    return@addSnapshotListener
                }

                close(firebaseFirestoreException)
            } else {
                trySend(querySnapshot!!)
            }
        }
        awaitClose {
            callback.remove()
        }
    }
}

/**
 * Allows for writing data to Firestore while offline and still receiving a callback.
 * If offline, Firestore callbacks (OnSuccess, OnFailure, etc.) will not be received until online again.
 * This function waits to see if data was successfully written to CACHE and then returns with Result
 * instead of relying on the base Firestore callbacks.
 **/
suspend fun DocumentReference.setAsync(data: Any): Result<Unit> =
    callbackFlow<Result<Unit>> {
        this@setAsync.set(data)
        val callback = this@setAsync.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, e ->
            if (e != null) {
                Log.e(
                    "firestore-extensions",
                    "DocSnapshot changed. Encountered Firestore Exception.",
                    e
                )
                trySend(Result.failure(e))
            } else {
                if (snapshot != null && snapshot.exists()) {
                    if (snapshot.metadata.hasPendingWrites()) {
                        trySend(Result.success(Unit))
                    }
                } else {
                    Log.e(
                        "firestore-extensions",
                        "DocSnapshot changed. Snapshot is NULL or doesn't exist."
                    )
                    trySend(Result.failure(NullPointerException("Snapshot for DocumentReference with ID '${this@setAsync.id}' is null or doesn't exist.")))
                }
            }
        }
        awaitClose {
            callback.remove()
        }
    }.first()

/**
 * Paginate a query, fetching new documents when scrolling to the end of a list.
 * This function assumes that you are showing all items from the result in a scrollable container.
 * @param lastVisibleItem A flow that contains the index of the last visible item on screen.
 * More items will be fetched when lastVisibleItem equals the size of the last emitted list.
 * @param initialLimit how many items to initially fetch.
 * @param futureLimit how many items to fetch on subsequent fetches.
 * @param update: Pair<DocumentId as String, Timestamp as Long>.
 * Optionally use this flow to refetch/update a specific document from the cache based on id.
 */
fun Query.paginate(
    lastVisibleItem: Flow<Int>,
    initialLimit: Long,
    futureLimit: Long,
    update: Flow<Pair<String, Long>?>? = null
): Flow<List<DocumentSnapshot>> = flow {
    val documents = mutableListOf<DocumentSnapshot>()
    val lastUpdate = MutableStateFlow<Pair<String, Long>?>(null)
    documents.addAll(
        suspendCoroutine { c ->
            this@paginate.limit(initialLimit).get()
                .addOnSuccessListener { c.resume(it.documents) }
        }
    )
    emit(documents)
    combineTransform(update ?: flowOf(null), lastVisibleItem) { update, lastVisible ->
        update?.takeIf { update.second != lastUpdate.value?.second }?.first.let { updateId -> documents.indexOfFirst { doc -> doc.id == updateId } }
            .takeIf { it > -1 }?.let { index ->
                documents[index].reference.fetchCacheOrNull()?.let { cached ->
                    documents[index] = cached
                    emit(documents)
                    lastUpdate.value = update
                }
            }
        if (lastVisible == documents.size && documents.size > 0) {
            this@paginate.limit(futureLimit)
                .startAfter(documents.last())
                .fetchOrNull()?.documents?.let { docs ->
                    documents.addAll(docs)
                }
            emit(documents)
        }
    }.collect { docs ->
        emit(docs.toList())
    }
}


fun DocumentSnapshot.getLongOrZero(field: String) =
    this[field]?.toString()?.toDoubleOrNull()?.toLong() ?: 0L

fun DocumentSnapshot.getLongOrNull(field: String) =
    this[field]?.toString()?.toDoubleOrNull()?.toLong()

fun DocumentSnapshot.getIntOrZero(field: String) =
    this[field]?.toString()?.toDoubleOrNull()?.toInt() ?: 0

fun DocumentSnapshot.getIntOrNull(field: String) =
    this[field]?.toString()?.toDoubleOrNull()?.toInt()

fun DocumentSnapshot.getDoubleOrZero(field: String) =
    this[field]?.toString()?.toDoubleOrNull() ?: 0.0

fun DocumentSnapshot.getDoubleOrNull(field: String) = this[field]?.toString()?.toDoubleOrNull()

fun DocumentSnapshot.getStringOrEmpty(field: String) = this[field]?.toString() ?: ""
fun DocumentSnapshot.getStringOrNull(field: String) = this[field]?.toString()

fun DocumentSnapshot.getBooleanOrFalse(field: String) = (this[field] as? Boolean) ?: false
fun DocumentSnapshot.getBooleanOrNull(field: String) = (this[field] as? Boolean)

fun DocumentSnapshot.getTimestampOrNull(field: String) = (this[field] as? Timestamp)
fun DocumentSnapshot.getGeoPointOrNull(field: String) = (this[field] as? GeoPoint)

fun DocumentSnapshot.getWildcardListOrNull(field: String) = (this[field] as? List<*>)
inline fun <reified T> DocumentSnapshot.getListOrNull(field: String): List<T>? =
    getWildcardListOrNull(field)?.filterIsInstance<T>()

inline fun <reified T> DocumentSnapshot.getListOrEmpty(field: String): List<T> =
    getListOrNull(field) ?: emptyList()