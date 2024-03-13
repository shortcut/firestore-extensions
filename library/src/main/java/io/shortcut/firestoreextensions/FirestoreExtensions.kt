package io.shortcut.firestoreextensions

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

suspend fun CollectionReference.fetch(source: Source = Source.DEFAULT) = suspendCoroutine<QuerySnapshot> { c ->
    get(source).addOnSuccessListener { collection -> c.resume(collection) }
        .addOnFailureListener { e -> c.resumeWithException(e) }
}

suspend fun Query.fetch(source: Source = Source.DEFAULT) = suspendCoroutine<QuerySnapshot> { c ->
    get(source).addOnSuccessListener { snapshot -> c.resume(snapshot) }
        .addOnFailureListener { e -> c.resumeWithException(e) }
}

suspend fun DocumentReference.fetch(source: Source = Source.DEFAULT) = suspendCoroutine<DocumentSnapshot> { c ->
    get(source).addOnSuccessListener { snapshot -> c.resume(snapshot) }
        .addOnFailureListener { e -> c.resumeWithException(e) }
}

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
                Log.e("firestore-extensions", "DocSnapshot changed. Encountered Firestore Exception.", e)
                trySend(Result.failure(e))
            } else {
                if (snapshot != null && snapshot.exists()) {
                    if (snapshot.metadata.hasPendingWrites()) {
                        trySend(Result.success(Unit))
                    }
                } else {
                    Log.e("firestore-extensions", "DocSnapshot changed. Snapshot is NULL or doesn't exist.")
                    trySend(Result.failure(NullPointerException("Snapshot for DocumentReference with ID '${this@setAsync.id}' is null or doesn't exist.")))
                }
            }
        }
        awaitClose {
            callback.remove()
        }
    }.first()

fun Query.paginate(lastVisibleItem: Flow<Int>, limit: Long = 25): Flow<List<DocumentSnapshot>> = flow {
    val documents = mutableListOf<DocumentSnapshot>()
    documents.addAll(
        suspendCoroutine { c ->
            this@paginate.limit(limit).get().addOnSuccessListener { c.resume(it.documents) }
        }
    )
    emit(documents)
    lastVisibleItem.transform { lastVisible ->
        if (lastVisible == documents.size && documents.size > 0) {
            documents.addAll(
                suspendCoroutine { c ->
                    this@paginate.startAfter(documents.last())
                        .limit(limit)
                        .get()
                        .addOnSuccessListener {
                            c.resume(it.documents)
                        }
                }
            )
            emit(documents)
        }
    }.collect { docs ->
        emit(docs)
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
inline fun <reified T> DocumentSnapshot.getListOrNull(field: String): List<T>? = getWildcardListOrNull(field)?.filterIsInstance<T>()
inline fun <reified T> DocumentSnapshot.getListOrEmpty(field: String): List<T> = getListOrNull(field) ?: emptyList()