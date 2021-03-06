/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.dialer.calllog;

import android.app.ActionBar;
import android.app.ActionBar.LayoutParams;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.telephony.TelephonyManager;
import android.support.v13.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.text.TextUtils;
import android.view.View;
import android.view.View.OnFocusChangeListener;
import android.view.inputmethod.InputMethodManager;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.SearchView;
import android.widget.SearchView.OnCloseListener;
import android.widget.SearchView.OnQueryTextListener;
import android.util.Log;

import com.android.contacts.common.interactions.TouchPointManager;
import com.android.contacts.common.list.ViewPagerTabs;
import com.android.contacts.common.SimContactsConstants;
import com.android.contacts.common.util.PermissionsUtil;
import com.android.contacts.commonbind.analytics.AnalyticsUtil;
import com.android.dialer.DialtactsActivity;
import com.android.dialer.R;
import com.android.dialer.util.DialerUtils;
import com.android.dialer.voicemail.VoicemailStatusHelper;
import com.android.dialer.voicemail.VoicemailStatusHelperImpl;

public class CallLogActivity extends Activity implements ViewPager.OnPageChangeListener {
    private ViewPager mViewPager;
    private ViewPagerTabs mViewPagerTabs;
    private FragmentPagerAdapter mViewPagerAdapter;
    private CallLogFragment mAllCallsFragment;
    private CallLogFragment mMissedCallsFragment;
    private MSimCallLogFragment mMSimCallsFragment;
    private CallLogSearchFragment mSearchFragment;
    private SearchView mSearchView;
    private boolean mInSearchUi;
    private String[] mTabTitles;

    private static final int TAB_INDEX_ALL = 0;
    private static final int TAB_INDEX_MISSED = 1;

    private static final int TAB_INDEX_COUNT = 2;

    private boolean mIsResumed;

    private static final int TAB_INDEX_MSIM = 0;
    private static final int TAB_INDEX_COUNT_MSIM = 1;

    public class ViewPagerAdapter extends FragmentPagerAdapter {
        public ViewPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public long getItemId(int position) {
            return getRtlPosition(position);
        }

        @Override
        public Fragment getItem(int position) {
            switch (getRtlPosition(position)) {
                case TAB_INDEX_ALL:
                    return new CallLogFragment(CallLogQueryHandler.CALL_TYPE_ALL);
                case TAB_INDEX_MISSED:
                    return new CallLogFragment(Calls.MISSED_TYPE);
            }
            throw new IllegalStateException("No fragment at position " + position);
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            final CallLogFragment fragment =
                    (CallLogFragment) super.instantiateItem(container, position);
            switch (position) {
                case TAB_INDEX_ALL:
                    mAllCallsFragment = fragment;
                    break;
                case TAB_INDEX_MISSED:
                    mMissedCallsFragment = fragment;
                    break;
            }
            return fragment;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return mTabTitles[position];
        }

        @Override
        public int getCount() {
            return TAB_INDEX_COUNT;
        }
    }

    public class MSimViewPagerAdapter extends FragmentPagerAdapter {
        public MSimViewPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case TAB_INDEX_MSIM:
                    mMSimCallsFragment = new MSimCallLogFragment();
                    return mMSimCallsFragment;
            }
            throw new IllegalStateException("No fragment at position " + position);
        }

        @Override
        public int getCount() {
            return TAB_INDEX_COUNT_MSIM;
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            TouchPointManager.getInstance().setPoint((int) ev.getRawX(), (int) ev.getRawY());
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final ActionBar actionBar = getActionBar();
        actionBar.setDisplayShowHomeEnabled(true);
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(true);
        actionBar.setElevation(0);

        if ( TelephonyManager.getDefault().isMultiSimEnabled()) {
            initMSimCallLog();
            return;
        }

        setContentView(R.layout.call_log_activity);
        getWindow().setBackgroundDrawable(null);

        int startingTab = TAB_INDEX_ALL;
        final Intent intent = getIntent();
        if (intent != null) {
            final int callType = intent.getIntExtra(CallLog.Calls.EXTRA_CALL_TYPE_FILTER, -1);
            if (callType == CallLog.Calls.MISSED_TYPE) {
                startingTab = TAB_INDEX_MISSED;
            }
        }

        mTabTitles = new String[TAB_INDEX_COUNT];
        mTabTitles[0] = getString(R.string.call_log_all_title);
        mTabTitles[1] = getString(R.string.call_log_missed_title);

        mViewPager = (ViewPager) findViewById(R.id.call_log_pager);

        mViewPagerAdapter = new ViewPagerAdapter(getFragmentManager());
        mViewPager.setAdapter(mViewPagerAdapter);
        mViewPager.setOffscreenPageLimit(1);
        mViewPager.setOnPageChangeListener(this);

        mViewPagerTabs = (ViewPagerTabs) findViewById(R.id.viewpager_header);

        mViewPagerTabs.setViewPager(mViewPager);
        mViewPager.setCurrentItem(startingTab);
    }

    @Override
    public void onAttachFragment(Fragment fragment) {
        if (fragment instanceof CallLogSearchFragment) {
            if (mViewPagerAdapter != null) {
                mSearchFragment = (CallLogSearchFragment) fragment;
                setupSearchUi();
            }
        }
    }

    @Override
    protected void onResume() {
        mIsResumed = true;
        super.onResume();
        sendScreenViewForChildFragment(mViewPager.getCurrentItem());
    }

    @Override
    protected void onPause() {
        mIsResumed = false;
        super.onPause();
        if (mInSearchUi) {
            exitSearchUi();
        }
    }

    private void initMSimCallLog() {
        setContentView(R.layout.msim_call_log_activity);
        getWindow().setBackgroundDrawable(null);

        mViewPager = (ViewPager) findViewById(R.id.call_log_pager);

        mViewPagerAdapter = new MSimViewPagerAdapter(getFragmentManager());
        mViewPager.setAdapter(mViewPagerAdapter);
        mViewPager.setOffscreenPageLimit(1);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        final MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.call_log_options, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        final MenuItem itemDeleteAll = menu.findItem(R.id.delete_all);
        final MenuItem itemSearchCallLog = menu.findItem(R.id.search_calllog);
        if (mInSearchUi) {
            if (itemDeleteAll != null) {
                itemDeleteAll.setVisible(false);
            }
            if (itemSearchCallLog != null) {
                itemSearchCallLog.setVisible(false);
            }
        } else {
            if (mSearchFragment != null && itemSearchCallLog != null) {
                final CallLogAdapter adapter = mSearchFragment.getAdapter();
                itemSearchCallLog.setVisible(adapter != null
                        && !adapter.isEmpty());
            }
            // If onPrepareOptionsMenu is called before fragments loaded. Don't do anything.
            if (mAllCallsFragment != null && itemDeleteAll != null) {
                // If onPrepareOptionsMenu is called before fragments are loaded, don't do anything.
                final CallLogAdapter adapter = mAllCallsFragment.getAdapter();
                itemDeleteAll.setVisible(adapter != null && !adapter.isEmpty());
            }
        }

        if (mMSimCallsFragment != null && itemDeleteAll != null) {
            final CallLogAdapter adapter = mMSimCallsFragment.getAdapter();
            itemDeleteAll.setVisible(adapter != null && !adapter.isEmpty());
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                final Intent intent = new Intent(this, DialtactsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                return true;
            case R.id.delete_all:
                onDeleteCallLog();
                return true;
            case R.id.search_calllog:
                enterSearchUi();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void onDeleteCallLog() {
        startActivity(new Intent(SimContactsConstants.ACTION_MULTI_PICK_CALL));
    }

    @Override
    public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
        mViewPagerTabs.onPageScrolled(position, positionOffset, positionOffsetPixels);
    }

    @Override
    public void onPageSelected(int position) {
        if (mIsResumed) {
            sendScreenViewForChildFragment(position);
        }
        mViewPagerTabs.onPageSelected(position);
    }

    @Override
    public void onPageScrollStateChanged(int state) {
        mViewPagerTabs.onPageScrollStateChanged(state);
    }

    private void sendScreenViewForChildFragment(int position) {
        AnalyticsUtil.sendScreenView(CallLogFragment.class.getSimpleName(), this,
                getFragmentTagForPosition(position));
    }

    /**
     * Returns the fragment located at the given position in the {@link ViewPagerAdapter}. May
     * be null if the position is invalid.
     */
    private String getFragmentTagForPosition(int position) {
        switch (position) {
            case TAB_INDEX_ALL:
                return "All";
            case TAB_INDEX_MISSED:
                return "Missed";
        }
        return null;
    }

    private int getRtlPosition(int position) {
        if (DialerUtils.isRtl()) {
            return mViewPagerAdapter.getCount() - 1 - position;
        }
        return position;
    }

    private void enterSearchUi() {
        mInSearchUi = true;
        if (mSearchFragment == null) {
            addSearchFragment();
            return;
        }
        mSearchFragment.setUserVisibleHint(true);
        final FragmentTransaction transaction = getFragmentManager()
                .beginTransaction();
        transaction.show(mSearchFragment);
        transaction.commitAllowingStateLoss();
        getFragmentManager().executePendingTransactions();
        setupSearchUi();
    }

    private void setupSearchUi() {
        if (mSearchView == null) {
            prepareSearchView();
        }
        final ActionBar actionBar = getActionBar();

        mSearchView.setQuery(null, true);
        mSearchView.requestFocus();

        actionBar.setDisplayShowCustomEnabled(true);
        if (mMSimCallsFragment != null) {
            updateMSimFragmentVisibility(false);
        } else {
            for (int i = 0; i < mViewPagerAdapter.getCount(); i++) {
                updateFragmentVisibility(i, false /* not visible */);
            }
        }
        mViewPager.setVisibility(View.GONE);
        if (mViewPagerTabs != null) {
            mViewPagerTabs.setVisibility(View.GONE);
        }
        // We need to call this and onActionViewCollapsed() manually, since we
        // are using a custom
        // layout instead of asking the search menu item to take care of
        // SearchView.
        mSearchView.onActionViewExpanded();
    }

    private void updateFragmentVisibility(int position, boolean visibility) {
        if (position >= TAB_INDEX_ALL) {
            final Fragment fragment = getFragmentAt(position);
            if (fragment != null) {
                fragment.setMenuVisibility(visibility);
                fragment.setUserVisibleHint(visibility);
            }
        }
    }

    private void updateMSimFragmentVisibility(boolean visibility) {
        if (mMSimCallsFragment != null) {
            mMSimCallsFragment.setMenuVisibility(visibility);
            mMSimCallsFragment.setUserVisibleHint(visibility);
        }
    }

    private Fragment getFragmentAt(int position) {
        switch (position) {
        case TAB_INDEX_ALL:
            return mAllCallsFragment;
        case TAB_INDEX_MISSED:
            return mMissedCallsFragment;
        default:
            throw new IllegalStateException("Unknown fragment index: "
                    + position);
        }
    }

    private void addSearchFragment() {
        if (mSearchFragment != null) {
            return;
        }
        final FragmentTransaction ft = getFragmentManager().beginTransaction();
        final Fragment searchFragment = new CallLogSearchFragment();
        searchFragment.setUserVisibleHint(false);
        ft.add(R.id.calllog_frame, searchFragment);
        ft.commitAllowingStateLoss();
    }

    private void prepareSearchView() {
        final View searchViewLayout = getLayoutInflater().inflate(
                R.layout.search_action_bar, null);
        mSearchView = (SearchView) searchViewLayout
                .findViewById(R.id.search_view);
        mSearchView.setOnQueryTextListener(mPhoneSearchQueryTextListener);
        mSearchView.setOnCloseListener(mPhoneSearchCloseListener);
        mSearchView.setQueryHint(getString(R.string.calllog_search_hint));
        mSearchView.setIconifiedByDefault(true);
        mSearchView.setIconified(false);

        mSearchView
                .setOnQueryTextFocusChangeListener(new OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View view, boolean hasFocus) {
                        if (hasFocus) {
                            showInputMethod(view.findFocus());
                        }
                    }
                });

        getActionBar().setCustomView(
                searchViewLayout,
                new LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT));
    }

    private void showInputMethod(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            if (!imm.showSoftInput(view, 0)) {
            }
        }
    }

    private void hideInputMethod(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (imm != null && view != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    /**
     * Listener used to send search queries to the phone search fragment.
     */
    private final OnQueryTextListener mPhoneSearchQueryTextListener = new OnQueryTextListener() {
        @Override
        public boolean onQueryTextSubmit(String query) {
            View view = getCurrentFocus();
            if (view != null) {
                hideInputMethod(view);
                view.clearFocus();
            }
            return true;
        }

        @Override
        public boolean onQueryTextChange(String newText) {
            // Show search result with non-empty text. Show a bare list
            // otherwise.
            if (mSearchFragment != null) {
                mSearchFragment.setQueryString(newText);
            }
            return true;
        }
    };

    /**
     * Listener used to handle the "close" button on the right side of
     * {@link SearchView}. If some text is in the search view, this will clean
     * it up. Otherwise this will exit the search UI and let users go back to
     * usual Phone UI.
     *
     * This does _not_ handle back button.
     */
    private final OnCloseListener mPhoneSearchCloseListener = new OnCloseListener() {
        @Override
        public boolean onClose() {
            if (!TextUtils.isEmpty(mSearchView.getQuery())) {
                mSearchView.setQuery(null, true);
            }
            return true;
        }
    };

    @Override
    public void onBackPressed() {
        if (mInSearchUi) {
            // We should let the user go back to usual screens with tabs.
            exitSearchUi();
        } else {
            super.onBackPressed();
        }
    }

    private void exitSearchUi() {
        final ActionBar actionBar = getActionBar();
        if (mSearchFragment != null) {
            mSearchFragment.setUserVisibleHint(false);

            final FragmentTransaction transaction = getFragmentManager()
                    .beginTransaction();
            transaction.remove(mSearchFragment);
            mSearchFragment = null;
            transaction.commitAllowingStateLoss();
        }

        // We want to hide SearchView and show Tabs. Also focus on previously
        // selected one.
        actionBar.setDisplayShowCustomEnabled(false);
        if (mMSimCallsFragment != null) {
            updateMSimFragmentVisibility(true);
        } else {
            for (int i = 0; i < mViewPagerAdapter.getCount(); i++) {
                updateFragmentVisibility(i, i == mViewPager.getCurrentItem());
            }
        }
        mViewPager.setVisibility(View.VISIBLE);
        if (mViewPagerTabs != null) {
            mViewPagerTabs.setVisibility(View.VISIBLE);
        }
        hideInputMethod(getCurrentFocus());

        // Request to update option menu.
        invalidateOptionsMenu();

        // See comments in onActionViewExpanded()
        mSearchView.onActionViewCollapsed();
        mSearchView.clearFocus();
        mInSearchUi = false;
    }

}
