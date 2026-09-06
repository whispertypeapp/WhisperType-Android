package com.whispertype.android.core.contracts

/**
 * App-level facade for dictation. Implemented by the dictation workstream as a
 * process-scoped controller that the foreground service binds to. Used by the
 * accessibility service and features; never constructed by ViewModels.
 */
interface DictationBridge : DictationCommandSink, OverlayIntentHandler, SessionStateProvider