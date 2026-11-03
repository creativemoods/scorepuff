// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.config;

import pt.creativemoods.scorepuff.BuildConfig;

/** Keep Kotlin callers from incrementally inlining an earlier build's empty key constant. */
public final class ApiConfiguration {
    private ApiConfiguration() {}

    public static String omdbApiKey() {
        return BuildConfig.OMDB_API_KEY;
    }
}
