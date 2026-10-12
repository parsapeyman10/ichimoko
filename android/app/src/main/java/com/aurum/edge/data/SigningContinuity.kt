package com.aurum.edge.data

/** Android may require all current signers to match. Empty/partially overlapping sets fail closed. */
internal fun sameCurrentSigners(installed: Set<String>, candidate: Set<String>): Boolean =
    installed.isNotEmpty() && candidate.isNotEmpty() && installed == candidate
