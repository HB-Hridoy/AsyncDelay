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
import com.google.appinventor.components.runtime.OnDestroyListener; // Added for safe cleanup
import com.google.appinventor.components.runtime.util.YailList;
import com.google.appinventor.components.runtime.util.YailProcedure;
import com.hridoy.asyncdelay.helpers.Action;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@DesignerComponent(
		version = 12,
		description = "Ultimate non-blocking async execution engine utilizing native parameterized anonymous callback structures with global error interception.",
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

	@SimpleFunction(description = "Executes the callback block on the main thread after a specified delay.")
	public void PostDelay(int delayMs, final YailProcedure callback) {
		if (callback == null) return;
		uiHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				try {
					callback.call();
				} catch (Exception e) {
					ErrorOccurred("PostDelay", e.getMessage() != null ? e.getMessage() : e.toString());
				}
			}
		}, delayMs);
	}

	@SimpleFunction(description = "Schedules a callback to run after a specified delay, identified by a custom text tag. If a task with the same tag already exists, it is overridden.")
	public void PostDelayWithTag(final String tag, final int delayMs, final YailProcedure callback) {
		if (callback == null || tag == null) return;

		if (scheduledTasks.containsKey(tag)) {
			uiHandler.removeCallbacks(scheduledTasks.get(tag));
		}

		Runnable taskRunnable = new Runnable() {
			@Override
			public void run() {
				try {
					scheduledTasks.remove(tag);
					callback.call();
				} catch (Exception e) {
					ErrorOccurred("PostDelayWithTag [" + tag + "]", e.getMessage() != null ? e.getMessage() : e.toString());
				}
			}
		};

		scheduledTasks.put(tag, taskRunnable);
		uiHandler.postDelayed(taskRunnable, delayMs);
	}

	@SimpleFunction(description = "Manually cancels a pending delayed task associated with the specified tag before it can execute.")
	public void DelayCancel(final String tag) {
		if (tag == null) return;
		if (scheduledTasks.containsKey(tag)) {
			uiHandler.removeCallbacks(scheduledTasks.remove(tag));
		}
	}

	@SimpleFunction(description = "Schedules a parameterized callback to run after a delay. Takes a list of arguments and passes them directly into the callback block, preserving their exact values at the moment the timer started.")
	public void PostDelayWithArgs(final int delayMs, final YailList arguments, final YailProcedure callback) {
		if (callback == null || arguments == null) return;

		final Object[] argsArray = arguments.toArray();

		uiHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				try {
					callback.call(argsArray);
				} catch (Exception e) {
					ErrorOccurred("PostDelayWithArgs", e.getMessage() != null ? e.getMessage() : e.toString());
				}
			}
		}, delayMs);
	}

	@SimpleFunction(description = "Synchronizes execution with the Android display hardware clock. Fires the callback exactly at the start of the next screen frame pass to handle pixel-perfect UI updates without stutter.")
	public void PostToNextFrame(final YailProcedure callback) {
		if (callback == null) return;

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

	@SimpleFunction(description = "Safely schedules the execution of a callback on the Main UI Thread. Essential for updating UI elements or calling native App Inventor blocks from background threads, external listeners, or third-party SDK events.")
	public void PostToMainThread(final YailProcedure callback) {
		if (callback == null) return;

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

	@SimpleFunction(description = "Starts a highly configurable repetitive loop. Set maxCycles to 0 for infinite execution. onCompleteCallback fires automatically if maxCycles is reached.")
	public void StartInterval(final String id, final int intervalMs, final int maxCycles, final YailProcedure callback, final YailProcedure onCompleteCallback) {
		if (callback == null || id == null || id.isEmpty()) return;

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

	@SimpleFunction(description = "Dynamically updates the execution frequency rate of an active interval loop without interrupting cycle states.")
	public void UpdateIntervalSpeed(final String id, final int newIntervalMs) {
		if (id == null || !intervalRunnableMap.containsKey(id)) return;
		intervalMsMap.put(id, newIntervalMs);
	}

	@SimpleFunction(description = "Returns an App Inventor List containing the text IDs of all running interval loops.")
	public YailList GetRunningIntervalIds() {
		return YailList.makeList(intervalRunnableMap.keySet());
	}

	@SimpleFunction(description = "Sequences tasks using a master list containing pairs of [delayMs, anonymous_block]. Passes the current step index to the callback.")
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

	@SimpleFunction(description = "Yields execution instantly, pushing the block to the bottom of the OS loop queue to prevent UI locking.")
	public void PostToMicroTask(final YailProcedure callback) {
		if (callback == null) return;
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

	@SimpleFunction(description = "Registers task tokens into a sync group. callback fires once all tokens invoke ResolveTask.")
	public void AwaitAll(final String groupId, final YailList tasks, final YailProcedure callback) {
		if (groupId == null || groupId.isEmpty() || callback == null) return;

		Set<Object> tokens = new HashSet<>();
		for (int i = 1; i <= tasks.size(); i++) {
			tokens.add(tasks.get(i));
		}

		if (tokens.isEmpty()) {
			try {
				callback.call();
			} catch (Exception e) {
				ErrorOccurred("AwaitAll [" + groupId + "]", e.getMessage() != null ? e.getMessage() : e.toString());
			}
			return;
		}

		awaitGroups.put(groupId, tokens);

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

	@SimpleFunction(description = "Marks a specific running task token within a group as completed.")
	public void ResolveTask(final String groupName, Object token) {
		if (!awaitGroups.containsKey(groupName)) return;

		Set<Object> tokens = awaitGroups.get(groupName);
		tokens.remove(token);

		if (tokens.isEmpty()) {
			Runnable cb = debounceMap.get("GROUP_CB_" + groupName);
			if (cb != null) {
				uiHandler.post(cb);
			}
		}
	}

	@SimpleFunction(description = "Executes the callback block ONLY if the specified gate tag is unlocked.")
	public void GuardGate(final String gateId, final YailProcedure callback) {
		if (callback == null || gateId == null || gateId.isEmpty()) return;
		if (lockedGates.contains(gateId)) return;

		try {
			callback.call();
		} catch (Exception e) {
			ErrorOccurred("GuardGate [" + gateId + "]", e.getMessage() != null ? e.getMessage() : e.toString());
		}
	}

	@SimpleFunction(description = "Locks a gate tag, blocking associated GuardGate executions.")
	public void LockGate(String gateId) {
		if (gateId != null && !gateId.isEmpty()) lockedGates.add(gateId);
	}

	@SimpleFunction(description = "Unlocks a gate tag, allowing guarded executions to pass.")
	public void UnlockGate(String gateId) {
		if (gateId != null) lockedGates.remove(gateId);
	}

	@SimpleFunction(description = "Asynchronously polls a condition block. When true, fires the executionCallback. Passes total attempts to condition.")
	public void WaitUntil(final int intervalMs, final YailProcedure conditionBlock, final YailProcedure callback) {
		if (conditionBlock == null || callback == null) return;
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

	@SimpleFunction(description = "Retries an action with exponential backoff on failure. Passes currentAttempt to the action block. Fires onFailureCallback if all retries fail.")
	public void RetryWithBackoff(final int initialDelayMs, final int maxRetries, final YailProcedure actionBlock, final YailProcedure onFailureCallback) {
		if (actionBlock == null) return;
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

	@SimpleFunction(description = "Resets countdown on consecutive calls. Fires only when input pauses.")
	public void Debounce(final String id, final int delayMs, final YailProcedure callback) {
		if (callback == null || id == null || id.isEmpty()) return;
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

	@SimpleFunction(description = "Limits method execution speed to at most once per interval window.")
	public void Throttle(final String id, final int intervalMs, final YailProcedure callback) {
		if (callback == null || id == null || id.isEmpty()) return;
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

	@SimpleFunction(description = "Manages the execution lifecycle of active async operations via prefixed action strings.")
	public void ControlTask(final String id, @Options(Action.class) String action) {
		if (id == null || action == null) return;

		String command = action.trim().toUpperCase();

		switch (command) {
			case "INTERVAL_PAUSE":
				if (intervalRunnableMap.containsKey(id)) {
					intervalPausedStates.put(id, true);
				}
				break;

			case "INTERVAL_RESUME":
				if (intervalRunnableMap.containsKey(id)) {
					intervalPausedStates.put(id, false);
				}
				break;

			case "INTERVAL_CANCEL":
				if (intervalRunnableMap.containsKey(id)) {
					uiHandler.removeCallbacks(intervalRunnableMap.remove(id));
					intervalMsMap.remove(id);
					intervalMaxCycles.remove(id);
					intervalCycleCounts.remove(id);
					intervalPausedStates.remove(id);
				}
				break;

			case "DEBOUNCE_CANCEL":
				if (debounceMap.containsKey(id)) {
					uiHandler.removeCallbacks(debounceMap.remove(id));
				}
				break;

			case "THROTTLE_CANCEL":
				if (throttleMap.containsKey(id)) {
					throttleMap.remove(id);
				}
				break;

			case "DELAY_CANCEL":
				if (scheduledTasks.containsKey(id)) {
					uiHandler.removeCallbacks(scheduledTasks.remove(id));
				}
				break;

			default:
				ErrorOccurred("ControlTask", "Invalid prefixed action command '" + action + "' passed for target ID: " + id);
				break;
		}
	}

	// ================================================================
	// PRIVATE HELPERS
	// ================================================================

	/**
	 * Validates callback is non-null and has the correct parameter count.
	 */
	private boolean validateCallback(String op, YailProcedure cb, int expected) {
		if (cb == null) {
			ErrorOccurred(op, "Callback is null");
			return false;
		}
		if (cb.numArgs() != expected) {
			ErrorOccurred(op, "Callback must have exactly " + expected + " parameter(s). Got " + cb.numArgs() + ".");
			return false;
		}
		return true;
	}
}