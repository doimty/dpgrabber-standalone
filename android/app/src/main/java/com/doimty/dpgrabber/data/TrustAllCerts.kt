package com.doimty.dpgrabber.data

/** Legacy trust-all support was removed. Production requests use the platform TLS trust store. */
internal object TrustAllCerts {
    const val certificateVerificationRequired: Boolean = true
}
