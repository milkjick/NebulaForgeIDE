package com.nebulaforge.core.session

import com.nebulaforge.core.projectmodel.RunConfigurationSpec

/** Authoritative Android run lifecycle. Illegal phase transitions are rejected. */
class RunExecutionStateMachine {
    enum class Action { RUN, STOP, RESTART }

    enum class Phase {
        QUEUED, RESOLVING_DEVICE, INSTALLING, INSTALLED, LAUNCHING, RUNNING,
        DEVICE_DISCONNECTED, PROCESS_EXITED, STOPPING, STOPPED, SUCCEEDED, FAILED, CANCELLED
    }

    fun allowed(state: SessionState, action: Action): Boolean = when (action) {
        Action.RUN -> state is SessionState.Idle || state is SessionState.Succeeded || state is SessionState.Failed || state === SessionState.Cancelled
        Action.STOP -> state is SessionState.Preparing || state is SessionState.Running
        Action.RESTART -> state is SessionState.Succeeded || state is SessionState.Failed || state === SessionState.Cancelled
    }

    fun initial(spec: RunConfigurationSpec): SessionState = SessionState.Preparing("准备 ${spec.name}")

    fun canTransition(from: Phase, to: Phase): Boolean = when (from) {
        Phase.QUEUED -> to == Phase.RESOLVING_DEVICE || to == Phase.CANCELLED || to == Phase.FAILED
        Phase.RESOLVING_DEVICE -> to == Phase.INSTALLING || to == Phase.FAILED || to == Phase.CANCELLED
        Phase.INSTALLING -> to == Phase.INSTALLED || to == Phase.FAILED || to == Phase.CANCELLED || to == Phase.DEVICE_DISCONNECTED
        Phase.INSTALLED -> to == Phase.LAUNCHING || to == Phase.FAILED || to == Phase.CANCELLED || to == Phase.DEVICE_DISCONNECTED
        Phase.LAUNCHING -> to == Phase.RUNNING || to == Phase.FAILED || to == Phase.CANCELLED || to == Phase.DEVICE_DISCONNECTED
        Phase.RUNNING -> to == Phase.PROCESS_EXITED || to == Phase.STOPPING || to == Phase.DEVICE_DISCONNECTED || to == Phase.FAILED
        Phase.PROCESS_EXITED -> to == Phase.SUCCEEDED || to == Phase.FAILED
        Phase.DEVICE_DISCONNECTED -> to == Phase.FAILED || to == Phase.CANCELLED
        Phase.STOPPING -> to == Phase.STOPPED || to == Phase.FAILED
        Phase.STOPPED, Phase.SUCCEEDED, Phase.FAILED, Phase.CANCELLED -> false
    }
}
