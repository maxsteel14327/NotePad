package com.nononsenseapps.notepad.fragments;

import com.googlecode.androidannotations.annotations.AfterViews;
import com.googlecode.androidannotations.annotations.EFragment;
import com.googlecode.androidannotations.annotations.ViewById;

import com.nononsenseapps.notepad.R;
import com.nononsenseapps.notepad.database.TaskList;
import com.nononsenseapps.notepad.fragments.DialogEditList.EditListDialogListener;
import com.nononsenseapps.notepad.prefs.MainPrefs;

import android.content.Context;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.CursorAdapter;
import android.support.v4.widget.SimpleCursorAdapter;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

/**
 * Displays many listfragments across a viewpager. Supports selecting a certain
 * one on startup.
 * 
 */
@EFragment(R.layout.fragment_tasklist_viewpager)
public class TaskListViewPagerFragment extends Fragment implements
		EditListDialogListener {

	public static final String START_LIST_ID = "start_list_id";

	@ViewById
	ViewPager pager;

	private SectionsPagerAdapter mSectionsPagerAdapter;
	SimpleCursorAdapter mTaskListsAdapter;

	boolean firstLoad = true;

	private long mListIdToSelect = -1;

	public static TaskListViewPagerFragment getInstance() {
		return getInstance(-1);
	}

	public static TaskListViewPagerFragment getInstance(final long startListId) {
		TaskListViewPagerFragment_ f = new TaskListViewPagerFragment_();
		Bundle args = new Bundle();
		args.putLong(START_LIST_ID, startListId);
		f.setArguments(args);
		return f;
	}

	public TaskListViewPagerFragment() {
		super();
	}

	public SectionsPagerAdapter getSectionsPagerAdapter() {
		return mSectionsPagerAdapter;
	}

	@Override
	public void onCreate(Bundle savedState) {
		super.onCreate(savedState);
		setHasOptionsMenu(true);

		mListIdToSelect = getArguments().getLong(START_LIST_ID, -1);
		if (savedState != null) {
			mListIdToSelect = savedState.getLong(START_LIST_ID);
		}

		// Adapter for list titles and ids
		mTaskListsAdapter = new SimpleCursorAdapter(getActivity(),
				android.R.layout.simple_dropdown_item_1line, null,
				new String[] { TaskList.Columns.TITLE },
				new int[] { android.R.id.text1 }, 0);
		// Adapter for view pager
		mSectionsPagerAdapter = new SectionsPagerAdapter(getFragmentManager(),
				mTaskListsAdapter);
	}

	@Override
	public void onStart() {
		super.onStart();

		// Load actual data
		getLoaderManager().restartLoader(0, null,
				new LoaderCallbacks<Cursor>() {

					@Override
					public Loader<Cursor> onCreateLoader(int arg0, Bundle arg1) {
						return new CursorLoader(getActivity(), TaskList.URI,
								new String[] { TaskList.Columns._ID,
										TaskList.Columns.TITLE }, null, null,
								getResources().getString(
										R.string.const_as_alphabetic,
										TaskList.Columns.TITLE));
					}

					@Override
					public void onLoadFinished(Loader<Cursor> arg0, Cursor c) {
						mTaskListsAdapter.swapCursor(c);
						if (firstLoad) {
							firstLoad = false;
							final int pos = mSectionsPagerAdapter
									.getItemPosition(mListIdToSelect);
							if (pos >= 0) {
								pager.setCurrentItem(pos);
							}
						}
					}

					@Override
					public void onLoaderReset(Loader<Cursor> arg0) {
						mTaskListsAdapter.swapCursor(null);
					}
				});
	}

	@AfterViews
	void setAdapter() {
		// Set adapters
		pager.setAdapter(mSectionsPagerAdapter);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.fragment_tasklists_viewpager, menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menu_createlist:
			// Show fragment
			DialogEditList_ dialog = DialogEditList_.getInstance();
			dialog.setListener(this);
			dialog.show(getFragmentManager(), "fragment_create_list");
			return true;
		default:
			return false;
		}
	}

	@Override
	public void onFinishEditDialog(final long id) {
		// open the list
		if (mSectionsPagerAdapter != null) {
			final int pos = mSectionsPagerAdapter.getItemPosition(id);
			if (pos > -1) {
				pager.setCurrentItem(pos, true);
			}
		}
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		outState.putLong(START_LIST_ID,
				mTaskListsAdapter.getItemId(pager.getCurrentItem()));
	}

	@Override
	public void onDestroy() {
		if (mSectionsPagerAdapter != null) {
			mSectionsPagerAdapter.destroy();
		}
		getLoaderManager().destroyLoader(0);

		super.onDestroy();
	}

	/**
	 * If temp list is > 0, returns it. Else, checks if a default list is set
	 * then returns that. If none set, then returns first (alphabetical) list
	 * Returns -1 if no lists in database.
	 * 
	 * Guarantees default list is valid
	 */
	public static long getAList(final Context context, final long tempList) {
		long returnList = tempList;

		if (returnList < 1) {
			// Then check if a default list is specified
			returnList = Long.parseLong(PreferenceManager
					.getDefaultSharedPreferences(context).getString(
							MainPrefs.KEY_DEFAULT_LIST, "-1"));
		}

		if (returnList > 0) {
			// See if it exists
			final Cursor c = context.getContentResolver().query(TaskList.URI,
					TaskList.Columns.FIELDS, TaskList.Columns._ID + " IS ?",
					new String[] { Long.toString(returnList) }, null);
			if (c.moveToFirst()) {
				returnList = c.getLong(0);
			}
			c.close();
		}

		if (returnList < 1) {
			// Fetch a valid list from database if previous attempts are invalid
			final Cursor c = context.getContentResolver().query(
					TaskList.URI,
					TaskList.Columns.FIELDS,
					null,
					null,
					context.getResources().getString(
							R.string.const_as_alphabetic,
							TaskList.Columns.TITLE));
			if (c.moveToFirst()) {
				returnList = c.getLong(0);
			}
			c.close();
		}

		return returnList;
	}

	public class SectionsPagerAdapter extends FragmentPagerAdapter {

		private final CursorAdapter wrappedAdapter;
		private final DataSetObserver subObserver;

		public SectionsPagerAdapter(final FragmentManager fm,
				final CursorAdapter wrappedAdapter) {
			super(fm);
			this.wrappedAdapter = wrappedAdapter;

			subObserver = new DataSetObserver() {
				@Override
				public void onChanged() {
					notifyDataSetChanged();
				}

				@Override
				public void onInvalidated() {
					// Probably destroying the loader
				}
			};

			if (wrappedAdapter != null)
				wrappedAdapter.registerDataSetObserver(subObserver);

		}

		public void destroy() {
			if (wrappedAdapter != null) {
				wrappedAdapter.unregisterDataSetObserver(subObserver);
			}
		}

		@Override
		public Fragment getItem(int pos) {
			long id = getItemId(pos);
			if (id < 0) return null;
			return TaskListFragment_.getInstance(id);
		}

		@Override
		public long getItemId(int position) {
			long id = -1;
			if (wrappedAdapter != null) {
				Cursor c = (Cursor) wrappedAdapter.getItem(position);
				if (c != null && !c.isAfterLast() && !c.isBeforeFirst()) {
					id = c.getLong(0);
				}
			}
			return id;
		}

		@Override
		public int getCount() {
			if (wrappedAdapter != null)
				return wrappedAdapter.getCount();
			else
				return 0;
		}

		@Override
		public CharSequence getPageTitle(int position) {
			if (position >= getCount()) return null;
			CharSequence title = null;
			if (wrappedAdapter != null) {
				Cursor c = (Cursor) wrappedAdapter.getItem(position);
				if (c != null && !c.isAfterLast() && !c.isBeforeFirst()) {
					title = c.getString(1);
				}
			}

			return title;
		}

		/**
		 * {@inheritDoc}
		 * 
		 * Called when the host view is attempting to determine if an item's
		 * position has changed. Returns POSITION_UNCHANGED if the position of
		 * the given item has not changed or POSITION_NONE if the item is no
		 * longer present in the adapter.
		 * 
		 * Argument is the object previously returned by instantiateItem
		 */
		@Override
		public int getItemPosition(Object object) {
			Fragment f = (Fragment) object;
			long listId = f.getArguments().getLong(TaskListFragment.LIST_ID);
			return getItemPosition(listId);
		}

		/**
		 * Returns a negative number if id wasn't found in adapter
		 */
		public int getItemPosition(final long listId) {
			int length = getCount();
			int result = POSITION_NONE;
			int position;
			for (position = 0; position < length; position++) {
				if (listId == getItemId(position)) {
					result = position;
					break;
				}
			}

			return result;
		}
	}
}