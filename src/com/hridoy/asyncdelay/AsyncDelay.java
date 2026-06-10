package com.hridoy.asyncdelay;

import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import com.google.appinventor.components.annotations.DesignerComponent;
import com.google.appinventor.components.annotations.Options;
import com.google.appinventor.components.annotations.SimpleEvent;
import com.google.appinventor.components.annotations.SimpleFunction;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.EventDispatcher;
import com.google.appinventor.components.runtime.OnDestroyListener;
import com.google.appinventor.components.runtime.util.YailList;
import com.google.appinventor.components.runtime.util.YailProcedure;

import com.hridoy.asyncdelay.helpers.Action;
import com.hridoy.asyncdelay.helpers.TaskType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@DesignerComponent(
		version = 17,
		versionName = "v1.0.1",
		description = "Ultimate high-performance, non-blocking asynchronous execution engine for App Inventor. Provides thread-safe, precise scheduling primitives including multi-instance looping intervals, timelines, debouncing, throttling, frame-synchronized UI updates, and atomic execution gatekeepers.",
		nonVisible = true,
		iconName = "icon.png"
)
public class AsyncDelay extends AndroidNonvisibleComponent implements OnDestroyListener {

	private final Handler uiHandler;
	private final Map<String, Runnable> debounceMap;
	private final Map<String, Long> throttleMap;
	private final Set<String> lockedGates;
	private final Map<String, Set<Object>> awaitGroups;
	private final Map<String, Runnable> scheduledTasks;

	// ================================================================
	// PARALLEL INTERVAL TRACKING STORAGE
	// ================================================================
	private final Map<String, Runnable> intervalRunnableMap;
	private final Map<String, Integer> intervalMsMap;
	private final Map<String, Integer> intervalCycleCounts;
	private final Map<String, Integer> intervalMaxCycles;
	private final Map<String, Boolean> intervalPausedStates;

	public AsyncDelay(ComponentContainer container) {
		super(container.$form());
		this.uiHandler = new Handler(Looper.getMainLooper());
		this.debounceMap = new HashMap<>();
		this.throttleMap = new HashMap<>();
		this.lockedGates = new HashSet<>();
		this.awaitGroups = new HashMap<>();
		this.scheduledTasks = new HashMap<>();

		// Initialize single-file interval maps
		this.intervalRunnableMap = new HashMap<>();
		this.intervalMsMap = new HashMap<>();
		this.intervalCycleCounts = new HashMap<>();
		this.intervalMaxCycles = new HashMap<>();
		this.intervalPausedStates = new HashMap<>();

		// Register lifecycle hook into container environment Form
		form.registerForOnDestroy(this);
	}

	// ================================================================
	// LIFECYCLE EVENTS
	// ================================================================

	@Override
	public void onDestroy() {
		uiHandler.removeCallbacksAndMessages(null);

		debounceMap.clear();
		throttleMap.clear();
		lockedGates.clear();
		awaitGroups.clear();
		scheduledTasks.clear();

		intervalRunnableMap.clear();
		intervalMsMap.clear();
		intervalCycleCounts.clear();
		intervalMaxCycles.clear();
		intervalPausedStates.clear();
	}

	@SimpleEvent(description = "Fires automatically when an asynchronous operation or callback execution catches a runtime exception.")
	public void ErrorOccurred(String functionName, String errorMessage) {
		EventDispatcher.dispatchEvent(this, "ErrorOccurred", functionName, errorMessage);
	}

	// ================================================================
	// CORE ASYNC FUNCTIONS
	// ================================================================

	@SimpleFunction(description =
			"Schedules a macro task block to execute on the main thread after a specified delay.\n" +
					"Leave the ID empty (\"\") for a standard single-use delay.\n" +
					"Provide a unique text ID to make it overwritable; consecutive calls with the same ID will reset the timer.\n" +
					".\n===============================================================\n.\n" +
					"Callback parameters:\n" +
					"  None (expects exactly 0 parameters)")
	public void PostDelay(final String id, final int delayMs, final YailProcedure callback) {
		if (!validateCallback("PostDelay", callback, 0)) return;

		// 1. Determine if this is an ID-tracked task or a fire-and-forget task
		final boolean hasId = (id != null && !id.trim().isEmpty());
		final String finalId = hasId ? id.trim() : "AUTOID_" + System.nanoTime() + "_" + callback.hashCode();

		// 2. If it has an ID and it's already pending, clear the old execution timer
		if (hasId && scheduledTasks.containsKey(finalId)) {
			uiHandler.removeCallbacks(scheduledTasks.get(finalId));
		}

		Runnable taskRunnable = new Runnable() {
			@Override
			public void run() {
				try {
					if (hasId) {
						scheduledTasks.remove(finalId);
					}
					callback.call();
				} catch (Exception e) {
					String context = hasId ? "PostDelay [" + finalId + "]" : "PostDelay";
					ErrorOccurred(context, e.getMessage() != null ? e.getMessage() : e.toString());
				}
			}
		};

		// 3. Track it if it needs to be accessible by ControlTask or an overwrite pass
		if (hasId) {
			scheduledTasks.put(finalId, taskRunnable);
		}

		uiHandler.postDelayed(taskRunnable, delayMs);
	}

	@SimpleFunction(description =
			"Schedules a parameterized task block to run after a specified delay.\n" +
					"Leave the ID empty (\"\") for a standard single-use delay.\n" +
					"Freezes values passed into the arguments list the moment this function is triggered.\n" +
					".\n===============================================================\n.\n" +
					"Callback parameters:\n" +
					"  Matches the exact length and item sequence of your arguments list parameter.")
	public void PostDelayWithArgs(final String id, final int delayMs, final YailList arguments, final YailProcedure callback) {
		if (arguments == null) {
			ErrorOccurred("PostDelayWithArgs", "Arguments list cannot be null.");
			return;
		}
		if (!validateCallback("PostDelayWithArgs", callback, arguments.size())) return;

		final boolean hasId = (id != null && !id.trim().isEmpty());
		final String finalId = hasId ? id.trim() : "AUTOID_ARGS_" + System.nanoTime() + "_" + callback.hashCode();

		if (hasId && scheduledTasks.containsKey(finalId)) {
			uiHandler.removeCallbacks(scheduledTasks.get(finalId));
		}

		final Object[] argsArray = arguments.toArray();

		Runnable taskRunnable = new Runnable() {
			@Override
			public void run() {
				try {
					if (hasId) {
						scheduledTasks.remove(finalId);
					}
					callback.call(argsArray);
				} catch (Exception e) {
					String context = hasId ? "PostDelayWithArgs [" + finalId + "]" : "PostDelayWithArgs";
					ErrorOccurred(context, e.getMessage() != null ? e.getMessage() : e.toString());
				}
			}
		};

		if (hasId) {
			scheduledTasks.put(finalId, taskRunnable);
		}

		uiHandler.postDelayed(taskRunnable, delayMs);
	}

	@SimpleFunction(description =
			"Synchronizes execution with the Android device hardware display pass subsystem clock.\n" +
					"Triggers the execution block immediately at the start of the next screen frame calculation loop.\n" +
					".\n===============================================================\n.\n" +
					"Callback parameters:\n" +
					"  None (expects exactly 0 parameters)")
	public void PostToNextFrame(final YailProcedure callback) {
		if (!validateCallback("PostToNextFrame", callback, 0)) return;

		Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
			@Override
			public void doFrame(long frameTimeNanos) {
				uiHandler.post(new Runnable() {
					@Override
					public void run() {
						try {
							callback.call();
						} catch (Exception e) {
							ErrorOccurred("PostToNextFrame", e.getMessage() != null ? e.getMessage() : e.toString());
						}
					}
				});
			}
		});
	}

	@SimpleFunction(description =
			"Safely schedules execution context logic straight onto the Main Application UI thread.\n" +
					"Essential for modifying layouts or UI components from background sockets or listeners.\n" +
					".\n===============================================================\n.\n" +
					"Callback parameters:\n" +
					"  None (expects exactly 0 parameters)")
	public void PostToMainThread(final YailProcedure callback) {
		if (!validateCallback("PostToMainThread", callback, 0)) return;

		uiHandler.post(new Runnable() {
			@Override
			public void run() {
				try {
					callback.call();
				} catch (Exception e) {
					ErrorOccurred("PostToMainThread", e.getMessage() != null ? e.getMessage() : e.toString());
				}
			}
		});
	}

	// ================================================================
	// ADVANCED INTERVAL LIFECYCLE ENGINE
	// ================================================================

	@SimpleFunction(description =
			"Starts an isolated, repeating interval loop tracked by a unique identifier ID.\n" +
					"Pass 0 for 'maxCycles' to configure a continuous, infinite processing loop.\n" +
					"-----------------------------------------------------------------------\n" +
					"Required Callbacks:\n" +
					"\n" +
					" 1. callback (Expects exactly 1 parameter)\n" +
					"    • Receives: 'currentCycle' (number) -> The current execution index, starting at 1.\n" +
					"    • Note: Place the repetitive blocks you want executed on every interval tick inside here.\n" +
					"\n" +
					" 2. onCompleteCallback (Expects exactly 0 parameters)\n" +
					"    • Triggers automatically ONLY when 'maxCycles' is reached and the loop terminates.\n" +
					"    • Note: Will never fire if 'maxCycles' is set to 0. Use this to handle post-loop cleanups.")
	public void StartInterval(final String id, final int intervalMs, final int maxCycles, final YailProcedure callback, final YailProcedure onCompleteCallback) {
		if (id == null || id.isEmpty()) {
			ErrorOccurred("StartInterval", "Interval ID identifier cannot be empty.");
			return;
		}
		if (!validateCallback("StartInterval [" + id + "] Core Loop", callback, 1)) return;
		if (onCompleteCallback != null && !validateCallback("StartInterval [" + id + "] OnComplete", onCompleteCallback, 0)) return;

		if (intervalRunnableMap.containsKey(id)) {
			uiHandler.removeCallbacks(intervalRunnableMap.remove(id));
		}

		intervalMsMap.put(id, intervalMs);
		intervalMaxCycles.put(id, maxCycles);
		intervalCycleCounts.put(id, 0);
		intervalPausedStates.put(id, false);

		final Runnable loopRunnable = new Runnable() {
			@Override
			public void run() {
				if (intervalRunnableMap.get(id) != this) return;

				boolean isPaused = intervalPausedStates.containsKey(id) && intervalPausedStates.get(id);

				if (!isPaused) {
					try {
						int currentCount = intervalCycleCounts.get(id) + 1;
						intervalCycleCounts.put(id, currentCount);

						callback.call(currentCount);

						int max = intervalMaxCycles.get(id);
						if (max > 0 && currentCount >= max) {
							intervalRunnableMap.remove(id);
							intervalMsMap.remove(id);
							intervalMaxCycles.remove(id);
							intervalCycleCounts.remove(id);
							intervalPausedStates.remove(id);

							if (onCompleteCallback != null) {
								onCompleteCallback.call();
							}
							return;
						}
					} catch (Exception e) {
						ErrorOccurred("Interval Loop [" + id + "]", e.getMessage() != null ? e.getMessage() : e.toString());
					}
				}

				int currentDelay = intervalMsMap.containsKey(id) ? intervalMsMap.get(id) : intervalMs;
				uiHandler.postDelayed(this, currentDelay);
			}
		};

		intervalRunnableMap.put(id, loopRunnable);
		uiHandler.postDelayed(loopRunnable, intervalMs);
	}

	@SimpleFunction(description =
			"Dynamically modifies the operational millisecond execution speed rate of an active interval loop.\n" +
					"Changes frequency tracking instantly without breaking processing threads or modifying counts.")
	public void UpdateIntervalSpeed(final String id, final int newIntervalMs) {
		if (id == null || !intervalRunnableMap.containsKey(id)) return;
		intervalMsMap.put(id, newIntervalMs);
	}

	@SimpleFunction(description =
			"Executes a chained timeline of delayed block tasks using a structured processing list.\n" +
					"Expects a list containing sub-lists formatted exactly as: [delayMs, block_procedure].\n" +
					"-----------------------------------------------------------------------\n" +
					"Callback parameters:\n" +
					"\n" +
					" • index (Expects exactly 1 parameter)\n" +
					"   • Receives: 'currentIndex' (number) -> The position of the currently executing step in your timeline list.\n" +
					"   • Note: Use this value if you need to track how far along the timeline sequence has progressed.")
	public void SequenceTimeline(final YailList timelinePairs) {
		if (timelinePairs == null || timelinePairs.size() == 0) return;
		executeTimelineStep(timelinePairs, 1);
	}

	private void executeTimelineStep(final YailList pairs, final int index) {
		if (index > pairs.size()) return;

		Object element = pairs.get(index);
		if (!(element instanceof YailList)) {
			ErrorOccurred("SequenceTimeline", "Element at index " + index + " inside master list is not a valid sub-list pair.");
			executeTimelineStep(pairs, index + 1);
			return;
		}

		final YailList currentPair = (YailList) element;
		if (currentPair.size() < 2) {
			ErrorOccurred("SequenceTimeline", "Step pair block at index " + index + " contains fewer than the required 2 configuration items.");
			executeTimelineStep(pairs, index + 1);
			return;
		}

		int currentDelay = 0;
		try {
			currentDelay = Integer.parseInt(currentPair.get(1).toString());
		} catch (NumberFormatException e) {
			ErrorOccurred("SequenceTimeline", "Failed parsing delay value at step " + index + ". Defaulting delay offset down to 0ms.");
			currentDelay = 0;
		}

		Object callbackObj = currentPair.get(2);
		if (!(callbackObj instanceof YailProcedure)) {
			ErrorOccurred("SequenceTimeline", "The executable block reference component at step " + index + " is structurally invalid or missing.");
			executeTimelineStep(pairs, index + 1);
			return;
		}

		final YailProcedure currentCallback = (YailProcedure) callbackObj;
		if (!validateCallback("SequenceTimeline [Step " + index + "]", currentCallback, 1)) {
			executeTimelineStep(pairs, index + 1);
			return;
		}

		uiHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				try {
					currentCallback.call(index);
				} catch (Exception e) {
					ErrorOccurred("SequenceTimeline [Step " + index + "]", e.getMessage() != null ? e.getMessage() : e.toString());
				}
				executeTimelineStep(pairs, index + 1);
			}
		}, currentDelay);
	}

	@SimpleFunction(description =
			"Yields processor command control instantly, placing the callback block loop at the bottom of the looper trace.\n" +
					"Forces operations to let layouts render completely before finishing calculations blocks.\n" +
					".\n===============================================================\n.\n" +
					"Callback parameters:\n" +
					"  None (expects exactly 0 parameters)")
	public void PostToMicroTask(final YailProcedure callback) {
		if (!validateCallback("PostToMicroTask", callback, 0)) return;
		uiHandler.post(new Runnable() {
			@Override
			public void run() {
				try {
					callback.call();
				} catch (Exception e) {
					ErrorOccurred("PostToMicroTask", e.getMessage() != null ? e.getMessage() : e.toString());
				}
			}
		});
	}

	@SimpleFunction(description =
			"Sets up a tracking queue sync layer. Pushes block task identifiers inside a destination repository array group.\n" +
					"The terminal callback triggers dynamically only when all items confirm completion via ResolveTask.\n" +
					".\n===============================================================\n.\n" +
					"Callback parameters:\n" +
					"  None (expects exactly 0 parameters)")
	public void AwaitAll(final String groupId, final YailList taskList, final YailProcedure callback) {
		if (groupId == null || groupId.isEmpty()) {
			ErrorOccurred("AwaitAll", "Group ID identifier cannot be empty.");
			return;
		}
		if (!validateCallback("AwaitAll [" + groupId + "]", callback, 0)) return;

		Set<Object> ids = new HashSet<>();
		for (int i = 1; i <= taskList.size(); i++) {
			ids.add(taskList.get(i));
		}

		if (ids.isEmpty()) {
			try {
				callback.call();
			} catch (Exception e) {
				ErrorOccurred("AwaitAll [" + groupId + "]", e.getMessage() != null ? e.getMessage() : e.toString());
			}
			return;
		}

		awaitGroups.put(groupId, ids);

		debounceMap.put("GROUP_CB_" + groupId, new Runnable() {
			@Override
			public void run() {
				try {
					callback.call();
				} catch (Exception e) {
					ErrorOccurred("AwaitAll Target Loop [" + groupId + "]", e.getMessage() != null ? e.getMessage() : e.toString());
				}
				awaitGroups.remove(groupId);
				debounceMap.remove("GROUP_CB_" + groupId);
			}
		});
	}

	@SimpleFunction(description =
			"Signals completion of a distinct token tracking item processing inside an active AwaitAll grouping map.")
	public void ResolveTask(final String groupId, Object taskId) {
		if (groupId == null || !awaitGroups.containsKey(groupId)) return;

		Set<Object> pendingIds = awaitGroups.get(groupId);
		pendingIds.remove(taskId);

		if (pendingIds.isEmpty()) {
			Runnable cb = debounceMap.get("GROUP_CB_" + groupId);
			if (cb != null) {
				uiHandler.post(cb);
			}
		}
	}

	@SimpleFunction(description =
			"Intercepts logic flow blocks. Only passes down execution if the designated target gate identifier ID is open.\n" +
					".\n===============================================================\n.\n" +
					"Callback parameters:\n" +
					"  None (expects exactly 0 parameters)")
	public void GuardGate(final String id, final YailProcedure callback) {
		if (id == null || id.isEmpty()) return;
		if (!validateCallback("GuardGate [" + id + "]", callback, 0)) return;
		if (lockedGates.contains(id)) return;

		try {
			lockedGates.add(id);
			callback.call();
		} catch (Exception e) {
			ErrorOccurred("GuardGate [" + id + "]", e.getMessage() != null ? e.getMessage() : e.toString());
		}
	}

	@SimpleFunction(description =
			"Asynchronously polls a condition block repeatedly until it evaluates to True, then executes a callback.\n" +
					"Perfect for waiting for background tasks, assets, or hardware connections to initialize before running code.\n" +
					"-----------------------------------------------------------------------\n" +
					"Required Callbacks:\n" +
					"\n" +
					" 1. conditionBlock (Expects exactly 1 parameter)\n" +
					"    • Receives: 'checkCount' (number) -> The current poll iteration count, starting at 1.\n" +
					"    • Note: Must return a boolean (True/False). Your app will keep polling as long as this returns False.\n" +
					"\n" +
					" 2. callback (Expects exactly 0 parameters)\n" +
					"    • Triggers automatically the exact moment the 'conditionBlock' returns True.\n" +
					"    • Note: Place the downstream actions you want to run after the condition is successfully met inside here.")
	public void WaitUntil(final int intervalMs, final YailProcedure conditionBlock, final YailProcedure callback) {
		if (!validateCallback("WaitUntil Condition-Block Check", conditionBlock, 1)) return;
		if (!validateCallback("WaitUntil Action-Callback Execution", callback, 0)) return;

		final Runnable checker = new Runnable() {
			private int checks = 0;
			@Override
			public void run() {
				try {
					checks++;
					Object result = conditionBlock.call(checks);
					boolean isTrue = (result instanceof Boolean) ? (Boolean) result : Boolean.parseBoolean(result.toString());
					if (isTrue) {
						callback.call();
					} else {
						uiHandler.postDelayed(this, intervalMs);
					}
				} catch (Exception e) {
					ErrorOccurred("WaitUntil Polling Handler", e.getMessage() != null ? e.getMessage() : e.toString());
				}
			}
		};
		uiHandler.post(checker);
	}

	@SimpleFunction(description =
			"Executes an operation recursively with an exponential delay backoff if failures occur.\n" +
					"Perfect for auto-retrying failed network requests without slamming the backend server.\n" +
					"-----------------------------------------------------------------------\n" +
					"Required Callbacks:\n" +
					"\n" +
					" 1. actionBlock (Expects exactly 1 parameter)\n" +
					"    • Receives: 'currentRetry' (number) -> The current attempt index, starting at 1.\n" +
					"    • Note: Place your main executable task inside here. If the task fails, call the retry trigger.\n" +
					"\n" +
					" 2. onFailureCallback (Expects exactly 0 parameters)\n" +
					"    • Triggers automatically ONLY when 'maxRetries' is reached and all attempts have failed.\n" +
					"    • Note: Use this to show an error message, update the UI, or gracefully cancel the process.")
	public void RetryWithBackoff(final int initialDelayMs, final int maxRetries, final YailProcedure actionBlock, final YailProcedure onFailureCallback) {
		if (!validateCallback("RetryWithBackoff Action-Step Block", actionBlock, 1)) return;
		if (onFailureCallback != null && !validateCallback("RetryWithBackoff OnFailure Lifecycle", onFailureCallback, 0)) return;

		executeRetryStep(initialDelayMs, maxRetries, 1, actionBlock, onFailureCallback);
	}

	private void executeRetryStep(final int currentDelay, final int maxRetries, final int currentAttempt, final YailProcedure action, final YailProcedure failureCallback) {
		if (currentAttempt > maxRetries) {
			if (failureCallback != null) {
				uiHandler.post(new Runnable() {
					@Override
					public void run() {
						try {
							failureCallback.call();
						} catch (Exception e) {
							ErrorOccurred("RetryWithBackoff [Failure Callback]", e.getMessage() != null ? e.getMessage() : e.toString());
						}
					}
				});
			}
			return;
		}

		uiHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				try {
					Object outcome = action.call(currentAttempt);
					boolean success = (outcome instanceof Boolean) ? (Boolean) outcome : Boolean.parseBoolean(outcome.toString());

					if (!success) {
						executeRetryStep(currentDelay * 2, maxRetries, currentAttempt + 1, action, failureCallback);
					}
				} catch (Exception e) {
					ErrorOccurred("RetryWithBackoff [Attempt " + currentAttempt + "]", e.getMessage() != null ? e.getMessage() : e.toString());
					executeRetryStep(currentDelay * 2, maxRetries, currentAttempt + 1, action, failureCallback);
				}
			}
		}, currentAttempt == 1 ? 0 : currentDelay);
	}

	@SimpleFunction(description =
			"Resets target block delay count triggers dynamically on successive inputs.\n" +
					"Holds off callback runtime logic blocks completely until active input sequences pause operations.\n" +
					".\n===============================================================\n.\n" +
					"Callback parameters:\n" +
					"  1) callback     (none: fires once input pauses completely, expects 0 parameters)")
	public void Debounce(final String id, final int delayMs, final YailProcedure callback) {
		if (id == null || id.isEmpty()) {
			ErrorOccurred("Debounce", "ID tag identifier cannot be empty.");
			return;
		}
		if (!validateCallback("Debounce [" + id + "]", callback, 0)) return;

		if (debounceMap.containsKey(id)) uiHandler.removeCallbacks(debounceMap.get(id));
		Runnable r = new Runnable() {
			@Override
			public void run() {
				try {
					callback.call();
				} catch (Exception e) {
					ErrorOccurred("Debounce [" + id + "]", e.getMessage() != null ? e.getMessage() : e.toString());
				}
				debounceMap.remove(id);
			}
		};
		debounceMap.put(id, r);
		uiHandler.postDelayed(r, delayMs);
	}

	@SimpleFunction(description =
			"Restricts task execution execution block metrics down to a single processing window calculation timeline.\n" +
					"Drops consecutive rapid block validation requests falling inside the constraint timer thresholds.\n" +
					".\n===============================================================\n.\n" +
					"Callback parameters:\n" +
					"  1) callback     (none: fires instantly if window is open, expects 0 parameters)")
	public void Throttle(final String id, final int intervalMs, final YailProcedure callback) {
		if (id == null || id.isEmpty()) {
			ErrorOccurred("Throttle", "ID tag identifier cannot be empty.");
			return;
		}
		if (!validateCallback("Throttle [" + id + "]", callback, 0)) return;

		long curr = System.currentTimeMillis();
		long last = throttleMap.containsKey(id) ? throttleMap.get(id) : 0;
		if (curr - last >= intervalMs) {
			throttleMap.put(id, curr);
			try {
				callback.call();
			} catch (Exception e) {
				ErrorOccurred("Throttle [" + id + "]", e.getMessage() != null ? e.getMessage() : e.toString());
			}
		}
	}

	@SimpleFunction(description =
			"Retrieves a structured list of all currently tracked task IDs filtered by the chosen subsystem.\n" +
					"-----------------------------------------------------------------------\n" +
					"Supported Query Options:\n" +
					" • Intervals   : Returns all active running or paused loop interval IDs.\n" +
					" • Delays      : Returns all pending post-delay or post-delay-with-args task IDs.\n" +
					" • Debounces   : Returns all currently active pending debouncing task IDs.\n" +
					" • Throttles   : Returns all execution tracking IDs locked inside a throttling window.\n" +
					" • LockedGates : Returns all gate IDs that are currently locked.")
	public YailList GetActiveIds(@Options(TaskType.class) String taskType) {
		if (taskType == null) return YailList.makeEmptyList();

		String command = taskType.trim().toUpperCase();

		switch (command) {
			case "INTERVALS":
				return YailList.makeList(intervalRunnableMap.keySet());

			case "DELAYS":
				return YailList.makeList(scheduledTasks.keySet());

			case "DEBOUNCES":
				return YailList.makeList(debounceMap.keySet());

			case "THROTTLES":
				return YailList.makeList(throttleMap.keySet());

			case "LOCKED_GATES":
				return YailList.makeList(lockedGates);

			default:
				ErrorOccurred("GetActiveIds", "Invalid task type filter query passed: " + taskType);
				return YailList.makeEmptyList();
		}
	}

	@SimpleFunction(description =
			"Unified administrative dashboard to manage the state and lifecycle of active processes.\n" +
					"-----------------------------------------------------------------------\n" +
					"Supported Actions:\n" +
					" • INTERVAL_PAUSE : Freezes execution cycles for the target interval ID.\n" +
					" • INTERVAL_RESUME : Unfreezes execution cycles for the target interval ID.\n" +
					" • INTERVAL_CANCEL : Forcefully terminates the interval and purges all cycle metadata.\n" +
					" • DEBOUNCE_CANCEL : Aborts a pending debounced execution timer.\n" +
					" • THROTTLE_CANCEL : Instantly releases a throttled execution lock.\n" +
					" • DELAY_CANCEL    : Cancels a pending scheduled post-delay or post-delay-with-args task.\n" +
					" • GATE_LOCK       : Closes an evaluation gate, dropping future GuardGate executions matching this ID.\n" +
					" • GATE_UNLOCK     : Opens an evaluation gate, allowing GuardGate blocks with this ID to pass through.")
	public void ManageTask(final String id, @Options(Action.class) String action) {
		if (id == null || action == null || id.trim().isEmpty()) return;

		String command = action.trim().toUpperCase();
		String finalId = id.trim();

		switch (command) {
			case "INTERVAL_PAUSE":
				if (intervalRunnableMap.containsKey(finalId)) {
					intervalPausedStates.put(finalId, true);
				}
				break;

			case "INTERVAL_RESUME":
				if (intervalRunnableMap.containsKey(finalId)) {
					intervalPausedStates.put(finalId, false);
				}
				break;

			case "INTERVAL_CANCEL":
				if (intervalRunnableMap.containsKey(finalId)) {
					uiHandler.removeCallbacks(intervalRunnableMap.remove(finalId));
					intervalMsMap.remove(finalId);
					intervalMaxCycles.remove(finalId);
					intervalCycleCounts.remove(finalId);
					intervalPausedStates.remove(finalId);
				}
				break;

			case "DEBOUNCE_CANCEL":
				if (debounceMap.containsKey(finalId)) {
					uiHandler.removeCallbacks(debounceMap.remove(finalId));
				}
				break;

			case "THROTTLE_CANCEL":
				if (throttleMap.containsKey(finalId)) {
					throttleMap.remove(finalId);
				}
				break;

			case "DELAY_CANCEL":
				if (scheduledTasks.containsKey(finalId)) {
					uiHandler.removeCallbacks(scheduledTasks.remove(finalId));
				}
				break;

			case "GATE_LOCK":
				lockedGates.add(finalId);
				break;

			case "GATE_UNLOCK":
				lockedGates.remove(finalId);
				break;

			default:
				ErrorOccurred("ManageTask", "Invalid prefixed action command '" + action + "' passed for target ID: " + finalId);
				break;
		}
	}

	// ================================================================
	// PRIVATE HELPERS
	// ================================================================

	/**
	 * Interceptively validates that a given macro block callback procedure is non-null
	 * and matches the precise structural parameter argument bounds expected by the native method.
	 * Dispatches context mapping descriptions back to the user blocks if structural violations occur.
	 */
	private boolean validateCallback(String op, YailProcedure cb, int expected) {
		if (cb == null) {
			ErrorOccurred(op, "Callback execution attempt failed: Target anonymous block reference is completely null.");
			return false;
		}
		if (cb.numArgs() != expected) {
			ErrorOccurred(op, "Structural Block Mismatch Error: The provided callback lambda block expects " + cb.numArgs() + " parameters, but the native execution loop system requires exactly " + expected + " parameter(s). Please correct the input signature on your block layout.");
			return false;
		}
		return true;
	}
}