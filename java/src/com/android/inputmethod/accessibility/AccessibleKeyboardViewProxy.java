/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.inputmethod.accessibility;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.inputmethodservice.InputMethodService;
import android.support.v4.view.AccessibilityDelegateCompat;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.accessibility.AccessibilityEventCompat;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;

import com.android.inputmethod.compat.ViewParentCompatUtils;
import com.android.inputmethod.keyboard.Key;
import com.android.inputmethod.keyboard.Keyboard;
import com.android.inputmethod.keyboard.KeyboardId;
import com.android.inputmethod.keyboard.LatinKeyboardView;
import com.android.inputmethod.keyboard.PointerTracker;
import com.android.inputmethod.latin.R;

public class AccessibleKeyboardViewProxy extends AccessibilityDelegateCompat {
    private static final AccessibleKeyboardViewProxy sInstance = new AccessibleKeyboardViewProxy();

    private InputMethodService mInputMethod;
    private FlickGestureDetector mGestureDetector;
    private LatinKeyboardView mView;
    private AccessibleKeyboardActionListener mListener;
    private AccessibilityEntityProvider mAccessibilityNodeProvider;

    private Key mLastHoverKey = null;

    public static void init(InputMethodService inputMethod) {
        sInstance.initInternal(inputMethod);
        sInstance.mListener = AccessibleInputMethodServiceProxy.getInstance();
    }

    public static AccessibleKeyboardViewProxy getInstance() {
        return sInstance;
    }

    private AccessibleKeyboardViewProxy() {
        // Not publicly instantiable.
    }

    private void initInternal(InputMethodService inputMethod) {
        final Paint paint = new Paint();
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(14.0f);
        paint.setAntiAlias(true);
        paint.setColor(Color.YELLOW);

        mInputMethod = inputMethod;
        mGestureDetector = new KeyboardFlickGestureDetector(inputMethod);
    }

    /**
     * Sets the view wrapped by this proxy.
     *
     * @param view The view to wrap.
     */
    public void setView(LatinKeyboardView view) {
        if (view == null) {
            // Ignore null views.
            return;
        }

        mView = view;

        // Ensure that the view has an accessibility delegate.
        ViewCompat.setAccessibilityDelegate(view, this);
    }

    /**
     * Proxy method for View.getAccessibilityNodeProvider(). This method is
     * called in SDK version 15 and higher to obtain the virtual node hierarchy
     * provider.
     *
     * @return The accessibility node provider for the current keyboard.
     */
    @Override
    public AccessibilityEntityProvider getAccessibilityNodeProvider(View host) {
        // Instantiate the provide only when requested. Since the system
        // will call this method multiple times it is a good practice to
        // cache the provider instance.
        if (mAccessibilityNodeProvider == null) {
            mAccessibilityNodeProvider = new AccessibilityEntityProvider(mView, mInputMethod);
        }
        return mAccessibilityNodeProvider;
    }

    /**
     * Receives hover events when accessibility is turned on in SDK versions ICS
     * and higher.
     *
     * @param event The hover event.
     * @return {@code true} if the event is handled
     */
    public boolean dispatchHoverEvent(MotionEvent event, PointerTracker tracker) {
        if (mGestureDetector.onHoverEvent(event, this, tracker))
            return true;

        return onHoverEventInternal(event, tracker);
    }

    /**
     * Handles touch exploration events when Accessibility is turned on.
     *
     * @param event The touch exploration hover event.
     * @return {@code true} if the event was handled
     */
    /* package */boolean onHoverEventInternal(MotionEvent event, PointerTracker tracker) {
        final int x = (int) event.getX();
        final int y = (int) event.getY();
        final Key key = tracker.getKeyOn(x, y);
        final Key previousKey = mLastHoverKey;

        mLastHoverKey = key;

        switch (event.getAction()) {
        case MotionEvent.ACTION_HOVER_ENTER:
        case MotionEvent.ACTION_HOVER_EXIT:
            return onHoverKey(key, event);
        case MotionEvent.ACTION_HOVER_MOVE:
            if (key != previousKey) {
                return onTransitionKey(key, previousKey, event);
            } else {
                return onHoverKey(key, event);
            }
        }

        return false;
    }

    /**
     * Simulates a transition between two {@link Key}s by sending a HOVER_EXIT
     * on the previous key, a HOVER_ENTER on the current key, and a HOVER_MOVE
     * on the current key.
     *
     * @param currentKey The currently hovered key.
     * @param previousKey The previously hovered key.
     * @param event The event that triggered the transition.
     * @return {@code true} if the event was handled.
     */
    private boolean onTransitionKey(Key currentKey, Key previousKey, MotionEvent event) {
        final int savedAction = event.getAction();

        event.setAction(MotionEvent.ACTION_HOVER_EXIT);
        onHoverKey(previousKey, event);

        event.setAction(MotionEvent.ACTION_HOVER_ENTER);
        onHoverKey(currentKey, event);

        event.setAction(MotionEvent.ACTION_HOVER_MOVE);
        final boolean handled = onHoverKey(currentKey, event);

        event.setAction(savedAction);

        return handled;
    }

    /**
     * Handles a hover event on a key. If {@link Key} extended View, this would
     * be analogous to calling View.onHoverEvent(MotionEvent).
     *
     * @param key The currently hovered key.
     * @param event The hover event.
     * @return {@code true} if the event was handled.
     */
    private boolean onHoverKey(Key key, MotionEvent event) {
        // Null keys can't receive events.
        if (key == null) {
            return false;
        }

        switch (event.getAction()) {
        case MotionEvent.ACTION_HOVER_ENTER:
            sendAccessibilityEventForKey(key, AccessibilityEventCompat.TYPE_VIEW_HOVER_ENTER);
            break;
        case MotionEvent.ACTION_HOVER_EXIT:
            sendAccessibilityEventForKey(key, AccessibilityEventCompat.TYPE_VIEW_HOVER_EXIT);
            break;
        }

        return true;
    }

    /**
     * Populates and sends an {@link AccessibilityEvent} for the specified key.
     *
     * @param key The key to send an event for.
     * @param eventType The type of event to send.
     */
    private void sendAccessibilityEventForKey(Key key, int eventType) {
        final AccessibilityEntityProvider nodeProvider = getAccessibilityNodeProvider(null);
        final AccessibilityEvent event = nodeProvider.createAccessibilityEvent(key, eventType);

        // Propagates the event up the view hierarchy.
        ViewParentCompatUtils.requestSendAccessibilityEvent(mView.getParent(), mView, event);
    }

    private class KeyboardFlickGestureDetector extends FlickGestureDetector {
        public KeyboardFlickGestureDetector(Context context) {
            super(context);
        }

        @Override
        public boolean onFlick(MotionEvent e1, MotionEvent e2, int direction) {
            if (mListener != null) {
                mListener.onFlickGesture(direction);
            }
            return true;
        }
    }

    /**
     * Notifies the user of changes in the keyboard shift state.
     */
    public void notifyShiftState() {
        final Keyboard keyboard = mView.getKeyboard();
        final KeyboardId keyboardId = keyboard.mId;
        final int elementId = keyboardId.mElementId;
        final Context context = mView.getContext();
        final CharSequence text;

        switch (elementId) {
        case KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCK_SHIFTED:
        case KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCKED:
            text = context.getText(R.string.spoken_description_shiftmode_locked);
            break;
        case KeyboardId.ELEMENT_ALPHABET_AUTOMATIC_SHIFTED:
        case KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED:
        case KeyboardId.ELEMENT_SYMBOLS_SHIFTED:
            text = context.getText(R.string.spoken_description_shiftmode_on);
            break;
        default:
            text = context.getText(R.string.spoken_description_shiftmode_off);
        }

        AccessibilityUtils.getInstance().speak(text);
    }

    /**
     * Notifies the user of changes in the keyboard symbols state.
     */
    public void notifySymbolsState() {
        final Keyboard keyboard = mView.getKeyboard();
        final Context context = mView.getContext();
        final KeyboardId keyboardId = keyboard.mId;
        final int elementId = keyboardId.mElementId;
        final int resId;

        switch (elementId) {
        case KeyboardId.ELEMENT_ALPHABET:
        case KeyboardId.ELEMENT_ALPHABET_AUTOMATIC_SHIFTED:
        case KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED:
        case KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCK_SHIFTED:
        case KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCKED:
            resId = R.string.spoken_description_mode_alpha;
            break;
        case KeyboardId.ELEMENT_SYMBOLS:
        case KeyboardId.ELEMENT_SYMBOLS_SHIFTED:
            resId = R.string.spoken_description_mode_symbol;
            break;
        case KeyboardId.ELEMENT_PHONE:
            resId = R.string.spoken_description_mode_phone;
            break;
        case KeyboardId.ELEMENT_PHONE_SYMBOLS:
            resId = R.string.spoken_description_mode_phone_shift;
            break;
        default:
            resId = -1;
        }

        if (resId < 0) {
            return;
        }

        final String text = context.getString(resId);
        AccessibilityUtils.getInstance().speak(text);
    }
}
