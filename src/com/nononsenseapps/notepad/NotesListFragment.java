package com.nononsenseapps.notepad;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;

import com.nononsenseapps.notepad.FragmentLayout.NotesEditorActivity;
import com.nononsenseapps.notepad.interfaces.DeleteActionListener;
import com.nononsenseapps.notepad.interfaces.OnEditorDeleteListener;
import com.nononsenseapps.notepad.interfaces.OnModalDeleteListener;
import com.nononsenseapps.notepad.sync.SyncAdapter;
import com.nononsenseapps.ui.NoteCheckBox;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.Cursor;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.SearchManager;

import android.support.v4.app.ListFragment;
import android.text.ClipboardManager;
import android.text.format.Time;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnKeyListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import android.support.v4.widget.SearchViewCompat;
import android.support.v4.widget.SimpleCursorAdapter;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.ActionBar.OnNavigationListener;
import com.actionbarsherlock.app.SherlockActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.actionbarsherlock.view.ActionMode;

public class NotesListFragment extends ListFragment implements OnModalDeleteListener,
		LoaderManager.LoaderCallbacks<Cursor>, OnSharedPreferenceChangeListener {
	private int mCurCheckPosition = 0;

	private static final String[] PROJECTION = new String[] {
			NotePad.Notes._ID, NotePad.Notes.COLUMN_NAME_TITLE,
			NotePad.Notes.COLUMN_NAME_NOTE,
			NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,
			NotePad.Notes.COLUMN_NAME_DUE_DATE,
			NotePad.Notes.COLUMN_NAME_GTASKS_STATUS };

	// public static final String SELECTEDPOS = "selectedpos";
	// public static final String SELECTEDID = "selectedid";

	// For logging and debugging
	private static final String TAG = "NotesListFragment";

	public static final int CHECK_SINGLE = 1;
	public static final int CHECK_MULTI = 2;
	public static final int CHECK_SINGLE_FUTURE = 3;

	private static final int STATE_EXISTING_NOTE = 1;
	private static final int STATE_LIST = 2;
	private int currentState = STATE_LIST;

	private static final String SAVEDPOS = "listSavedPos";
	private static final String SAVEDID = "listSavedId";
	private static final String SAVEDLISTID = "listSavedListId";
	private static final String SAVEDSTATE = "listSavedState";

	private static final String SHOULD_OPEN_NOTE = "shouldOpenNote";

	private long mCurId;

	private boolean idInvalid = false;

	// public SearchViewCompat mSearchView;
	public MenuItem mSearchItem;

	private String currentQuery = "";
	public int checkMode = CHECK_SINGLE;

	private ModeCallbackABS modeCallback;

	private long mCurListId = -1;

	private ListView lv;

	private FragmentActivity activity;

	private OnEditorDeleteListener onDeleteListener;

	private SimpleCursorAdapter mAdapter;

	private boolean autoOpenNote = false;
	private long newNoteIdToOpen = -1;
	private NotesEditorFragment landscapeEditor;

	private Menu mOptionsMenu;
	private View mRefreshIndeterminateProgressView = null;

	private BroadcastReceiver syncFinishedReceiver = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.getAction().equals(SyncAdapter.SYNC_STARTED)) {
				activity.runOnUiThread(new Runnable() {
					@Override
					public void run() {
						setRefreshActionItemState(true);
					}
				});
			} else {
				activity.runOnUiThread(new Runnable() {
					@Override
					public void run() {
						setRefreshActionItemState(false);
					}
				});
			}
		}
	};

	@Override
	public void onAttach(Activity activity) {
		if (FragmentLayout.UI_DEBUG_PRINTS)
			Log.d(TAG, "onAttach");
		super.onAttach(activity);
		this.activity = (FragmentActivity) activity;
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);

		if (FragmentLayout.LANDSCAPE_MODE) {
			autoOpenNote = true;
			landscapeEditor = (NotesEditorFragment) getFragmentManager()
					.findFragmentById(R.id.editor_container);
		} else {
			landscapeEditor = null;
		}

		lv = getListView();

		// Set adapter
		mAdapter = getThemedAdapter(null);
		setListAdapter(mAdapter);

		// Start out with a progress indicator.
		setListShown(false);

		// Set list preferences
		setSingleCheck();

		if (savedInstanceState != null) {
			currentState = savedInstanceState.getInt(SAVEDSTATE, STATE_LIST);
			mCurListId = savedInstanceState.getLong(SAVEDLISTID, -1);
			mCurCheckPosition = savedInstanceState.getInt(SAVEDPOS, 0);
			mCurId = savedInstanceState.getLong(SAVEDID, -1);
		} else {
			// Only display note in landscape
			if (FragmentLayout.LANDSCAPE_MODE)
				currentState = STATE_EXISTING_NOTE;
			else
				currentState = STATE_LIST;

			mCurCheckPosition = 0;
			mCurId = -1;
		}
	}

	public void handleNoteIntent(Intent intent) {
		Log.d(TAG, "handling intent");
		if (Intent.ACTION_EDIT.equals(intent.getAction())
				|| Intent.ACTION_VIEW.equals(intent.getAction())) {
			Log.d(TAG, "Selecting note");
			String newId = intent.getData().getPathSegments()
					.get(NotePad.Lists.ID_PATH_POSITION);
			long noteId = Long.parseLong(newId);
			if (noteId > -1) {
				newNoteIdToOpen = noteId;
			}
		} else if (Intent.ACTION_INSERT.equals(intent.getAction())) {
			// Get list to create note in first
			long listId = intent.getExtras().getLong(
					NotePad.Notes.COLUMN_NAME_LIST, -1);

			if (listId > -1) {
				Uri noteUri = FragmentLayout.createNote(
						activity.getContentResolver(), listId);

				if (noteUri != null) {
					newNoteIdToOpen = getNoteIdFromUri(noteUri);
				}
			}
		}
	}

	/**
	 * Will try to open the previously open note, but will default to first note
	 * if none was open
	 */
	private void showFirstBestNote() {
		if (mAdapter != null) {
			if (mAdapter.isEmpty()) {
				// DOn't do shit
			} else {
				currentState = STATE_EXISTING_NOTE;

				showNote(mCurCheckPosition);
			}
		}
	}

	private void setupSearchView() {
		if (FragmentLayout.UI_DEBUG_PRINTS)
			Log.d("NotesListFragment", "setup search view");
		// if (mSearchView != null) {
		// mSearchView.setIconifiedByDefault(true);
		// mSearchView.setOnQueryTextListener(this);
		// mSearchView.setSubmitButtonEnabled(false);
		// mSearchView.setQueryHint(getString(R.string.search_hint));
		// }
	}

	private int getPosOfId(long id) {
		int length = mAdapter.getCount();
		int position;
		for (position = 0; position < length; position++) {
			if (id == mAdapter.getItemId(position)) {
				break;
			}
		}
		if (position == length) {
			// Happens both if list is empty
			// and if id is -1
			position = -1;
		}
		return position;
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		// Inflate menu from XML resource
		// if (FragmentLayout.lightTheme)
		// inflater.inflate(R.menu.list_options_menu_light, menu);
		// else
		mOptionsMenu = menu;
		inflater.inflate(R.menu.list_options_menu, menu);

		// Get the SearchView and set the searchable configuration
		SearchManager searchManager = (SearchManager) activity
				.getSystemService(Context.SEARCH_SERVICE);
		mSearchItem = menu.findItem(R.id.menu_search);
		mSearchItem.setActionView(R.layout.collapsible_edittext);
		// mSearchView = (SearchView) mSearchItem.getActionView();
		// if (mSearchView != null)
		// mSearchView.setSearchableInfo(searchManager
		// .getSearchableInfo(activity.getComponentName()));
		// searchView.setIconifiedByDefault(true); // Do iconify the widget;
		// Don't
		// // expand by default
		// searchView.setSubmitButtonEnabled(false);
		// searchView.setOnCloseListener(this);
		// searchView.setOnQueryTextListener(this);

		setupSearchView();

		// Generate any additional actions that can be performed on the
		// overall list. In a normal install, there are no additional
		// actions found here, but this allows other applications to extend
		// our menu with their own actions.
	}

	@Override
	public void onPrepareOptionsMenu(Menu menu) {
		super.onPrepareOptionsMenu(menu);
	}

	public static int getNoteIdFromUri(Uri noteUri) {
		if (noteUri != null)
			return Integer.parseInt(noteUri.getPathSegments().get(
					NotePad.Notes.NOTE_ID_PATH_POSITION));
		else
			return -1;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menu_add:
			Uri noteUri = FragmentLayout.createNote(
					activity.getContentResolver(), mCurListId);

			if (noteUri != null) {
				newNoteIdToOpen = getNoteIdFromUri(noteUri);
			}

			return true;
		case R.id.menu_sync:
			if (FragmentLayout.UI_DEBUG_PRINTS)
				Log.d("NotesListFragment", "Sync");
			String accountName = PreferenceManager.getDefaultSharedPreferences(
					activity)
					.getString(NotesPreferenceFragment.KEY_ACCOUNT, "");
			boolean syncEnabled = PreferenceManager
					.getDefaultSharedPreferences(activity).getBoolean(
							NotesPreferenceFragment.KEY_SYNC_ENABLE, false);
			if (accountName != null && !accountName.equals("") && syncEnabled) {
				Account account = NotesPreferenceFragment.getAccount(
						AccountManager.get(activity), accountName);
				// Don't start a new sync if one is already going
				if (!ContentResolver.isSyncActive(account, NotePad.AUTHORITY)) {
					Bundle options = new Bundle();
					// This will force a sync regardless of what the setting is
					// in
					// accounts manager. Only use it here where the user has
					// manually
					// desired a sync to happen NOW.
					options.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
					ContentResolver.requestSync(account, NotePad.AUTHORITY,
							options);
				}
			} else {
				// The user might want to enable syncing. Open preferences
				Intent intent = new Intent();
				intent.setClass(activity, NotesPreferenceFragment.class);
				startActivity(intent);
			}
			return false; // Editor will listen for this also and saves when it
							// receives it
		case R.id.menu_clearcompleted:
			ContentValues values = new ContentValues();
			values.put(NotePad.Notes.COLUMN_NAME_MODIFIED, -1); // -1 anything
																// that isnt 0
																// or 1
																// indicates
																// that we dont
																// want to
																// change the
																// current value
			values.put(NotePad.Notes.COLUMN_NAME_LOCALHIDDEN, 1);
			activity.getContentResolver()
					.update(NotePad.Notes.CONTENT_URI,
							values,
							NotePad.Notes.COLUMN_NAME_GTASKS_STATUS
									+ " IS ? AND "
									+ NotePad.Notes.COLUMN_NAME_LIST + " IS ?",
							new String[] {
									getText(R.string.gtask_status_completed)
											.toString(),
									Long.toString(mCurListId) });
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// To get the call back to add items to the menu
		setHasOptionsMenu(true);

		// Listen to changes to sort order
		PreferenceManager.getDefaultSharedPreferences(activity)
				.registerOnSharedPreferenceChangeListener(this);

		if (savedInstanceState != null) {
			if (FragmentLayout.UI_DEBUG_PRINTS)
				Log.d("NotesListFragment", "onCreate saved not null");
			// mCurCheckPosition = savedInstanceState.getInt(SAVEDPOS);
			// mCurId = savedInstanceState.getLong(SAVEDID);
		}
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		if (FragmentLayout.UI_DEBUG_PRINTS)
			Log.d("NotesListFragment", "onSaveInstanceState");
		outState.putInt(SAVEDPOS, mCurCheckPosition);
		outState.putLong(SAVEDID, mCurId);
		outState.putInt(SAVEDSTATE, currentState);
		outState.putLong(SAVEDLISTID, mCurListId);
	}

	@Override
	public void onPause() {
		super.onPause();
		activity.unregisterReceiver(syncFinishedReceiver);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
	}

	@Override
	public void onResume() {
		super.onResume();
		if (FragmentLayout.UI_DEBUG_PRINTS)
			Log.d("NotesListFragment", "onResume");

		if (!FragmentLayout.LANDSCAPE_MODE) {
			currentState = STATE_LIST;
		}

		activity.registerReceiver(syncFinishedReceiver, new IntentFilter(
				SyncAdapter.SYNC_FINISHED));
		activity.registerReceiver(syncFinishedReceiver, new IntentFilter(
				SyncAdapter.SYNC_STARTED));

		String accountName = PreferenceManager.getDefaultSharedPreferences(
				activity).getString(NotesPreferenceFragment.KEY_ACCOUNT, "");
		// Sync state might have changed, make sure we're spinning when we
		// should
		if (accountName != null && !accountName.isEmpty())
			setRefreshActionItemState(ContentResolver.isSyncActive(
					NotesPreferenceFragment.getAccount(
							AccountManager.get(activity), accountName),
					NotePad.AUTHORITY));
	}

	@Override
	public void onListItemClick(ListView l, View v, int position, long id) {
		if (checkMode == CHECK_SINGLE)
			showNote(position);
	}

	/**
	 * Larger values than the list contains are re-calculated to valid
	 * positions. If list is empty, no note is opened.
	 */
	private void showNote(int index) {
		// if it's -1 to start with, we try with zero
		if (index < 0) {
			index = 0;
		}
		if (mAdapter != null) {
			while (index >= mAdapter.getCount()) {
				index = index - 1;
			}
			if (FragmentLayout.UI_DEBUG_PRINTS)
				Log.d(TAG, "showNote valid index to show is: " + index);

			if (index > -1) {
				mCurCheckPosition = index;
				selectPos(mCurCheckPosition);
				mCurId = mAdapter.getItemId(index);

				currentState = STATE_EXISTING_NOTE;

				if (FragmentLayout.LANDSCAPE_MODE) {
					if (FragmentLayout.UI_DEBUG_PRINTS)
						Log.d("NotesLIstFragmenT", "It is dualPane!");
					// We can display everything in-place with fragments, so
					// update
					// the list to highlight the selected item and show the
					// data.
					if (FragmentLayout.UI_DEBUG_PRINTS)
						Log.d("NotesListFragment", "Showing note: " + mCurId
								+ ", " + mCurCheckPosition);

					// Check what fragment is currently shown, replace if
					// needed.
					// NotesEditorFragment editor = (NotesEditorFragment)
					// getSupportFragmentManager()
					// .findFragmentById(R.id.editor);
					if (landscapeEditor != null) {
						// We want to know about changes here
						if (FragmentLayout.UI_DEBUG_PRINTS)
							Log.d("NotesListFragment", "Would open note here: "
									+ mCurId);
						landscapeEditor.displayNote(mCurId, mCurListId);
					}

				} else {
					if (FragmentLayout.UI_DEBUG_PRINTS)
						Log.d("NotesListFragment",
								"Showing note in SinglePane: id " + mCurId
										+ ", pos: " + mCurCheckPosition);
					// Otherwise we need to launch a new activity to display
					// the dialog fragment with selected text.
					Intent intent = new Intent();
					intent.setClass(activity, NotesEditorActivity.class);
					intent.putExtra(NotesEditorFragment.KEYID, mCurId);
					intent.putExtra(NotesEditorFragment.LISTID, mCurListId);

					startActivity(intent);
				}
			} else {
				// Empty search, do NOT display new note.
				mCurCheckPosition = 0;
				mCurId = -1;
				// Default show first note when search is cancelled.
			}
		}
	}

	/**
	 * Will re-list all notes, and show the note with closest position to
	 * original
	 */
	public void onDelete() {
		if (FragmentLayout.UI_DEBUG_PRINTS)
			Log.d(TAG, "onDelete");
		// Only do anything if id is valid!
		if (mCurId > -1) {
			if (onDeleteListener != null) {
				// Tell fragment to delete the current note
				onDeleteListener.onEditorDelete(mCurId);
			}
			if (FragmentLayout.LANDSCAPE_MODE) {
				autoOpenNote = true;
			}
			currentState = STATE_LIST;

			if (FragmentLayout.LANDSCAPE_MODE) {
			} else {
				// Get the id of the currently "selected" note
				// This matters if we switch to landscape mode
				reCalculateValidValuesAfterDelete();
			}
		}
	}

	private void reCalculateValidValuesAfterDelete() {
		int index = mCurCheckPosition;
		if (mAdapter != null) {
			while (index >= mAdapter.getCount()) {
				index = index - 1;
			}

			if (FragmentLayout.UI_DEBUG_PRINTS)
				Log.d(TAG, "ReCalculate valid index is: " + index);
			if (index == -1) {
				// Completely empty list.
				mCurCheckPosition = 0;
				mCurId = -1;
			} else { // if (index != -1) {
				mCurCheckPosition = index;
				mCurId = mAdapter.getItemId(index);
			}
		}
	}

	/**
	 * Recalculate note to select from id
	 */
	public void reSelectId() {
		int pos = getPosOfId(mCurId);
		if (FragmentLayout.UI_DEBUG_PRINTS)
			Log.d(TAG, "reSelectId id pos: " + mCurId + " " + pos);
		// This happens in a search. Don't destroy id information in selectPos
		// when it is invalid
		if (pos != -1) {
			mCurCheckPosition = pos;
			selectPos(mCurCheckPosition);
		}
	}

	private SimpleCursorAdapter getThemedAdapter(Cursor cursor) {
		// The names of the cursor columns to display in the view,
		// initialized
		// to the title column
		String[] dataColumns = { NotePad.Notes.COLUMN_NAME_GTASKS_STATUS,
				NotePad.Notes.COLUMN_NAME_TITLE,
				NotePad.Notes.COLUMN_NAME_NOTE,
				NotePad.Notes.COLUMN_NAME_DUE_DATE };

		// The view IDs that will display the cursor columns, initialized to
		// the TextView in noteslist_item.xml
		// My hacked adapter allows the boolean to be set if the string matches
		// gtasks string values for them. Needs id as well (set after first)
		int[] viewIDs = { R.id.itemDone, R.id.itemTitle, R.id.itemNote,
				R.id.itemDate };

		int themed_item = R.layout.noteslist_item;

		// Creates the backing adapter for the ListView.
		SimpleCursorAdapter adapter = new SimpleCursorAdapter(activity,
				themed_item, cursor, dataColumns, viewIDs, 0);

		final OnCheckedChangeListener listener = new OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView,
					boolean checked) {
				ContentValues values = new ContentValues();
				String status = getText(R.string.gtask_status_uncompleted)
						.toString();
				if (checked)
					status = getText(R.string.gtask_status_completed)
							.toString();
				values.put(NotePad.Notes.COLUMN_NAME_GTASKS_STATUS, status);

				long id = ((NoteCheckBox) buttonView).getNoteId();
				if (id > -1)
					activity.getContentResolver().update(
							NotesEditorFragment.getUriFrom(id), values, null,
							null);
			}
		};

		// In order to set the checked state in the checkbox
		adapter.setViewBinder(new SimpleCursorAdapter.ViewBinder() {
			public boolean setViewValue(View view, Cursor cursor,
					int columnIndex) {
				if (columnIndex == cursor
						.getColumnIndex(NotePad.Notes.COLUMN_NAME_GTASKS_STATUS)) {
					NoteCheckBox cb = (NoteCheckBox) view;
					cb.setOnCheckedChangeListener(null);
					long id = cursor.getLong(cursor
							.getColumnIndex(NotePad.Notes._ID));
					cb.setNoteId(id);
					String text = cursor.getString(cursor
							.getColumnIndex(NotePad.Notes.COLUMN_NAME_GTASKS_STATUS));

					if (text.equals(getText(R.string.gtask_status_completed))) {
						cb.setChecked(true);
					} else {
						cb.setChecked(false);
					}

					// Set a simple on change listener that updates the note on
					// changes.
					cb.setOnCheckedChangeListener(listener);

					return true;
				} else if (columnIndex == cursor
						.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE)
						|| columnIndex == cursor
								.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE)) {
					TextView tv = (TextView) view;
					// Set strike through on completed tasks
					String text = cursor.getString(cursor
							.getColumnIndex(NotePad.Notes.COLUMN_NAME_GTASKS_STATUS));
					if (text.equals(getText(R.string.gtask_status_completed))) {
						// Set appropriate BITMASK
						tv.setPaintFlags(tv.getPaintFlags()
								| Paint.STRIKE_THRU_TEXT_FLAG);
					} else {
						// Will clear strike-through. Just a BITMASK so do some
						// magic
						if (Paint.STRIKE_THRU_TEXT_FLAG == (tv.getPaintFlags() & Paint.STRIKE_THRU_TEXT_FLAG))
							tv.setPaintFlags(tv.getPaintFlags()
									- Paint.STRIKE_THRU_TEXT_FLAG);
					}

					// Return false so the normal call is used to set the text
					return false;
				}
				return false;
			}
		});

		return adapter;
	}

	public boolean onQueryTextChange(String query) {
		if (FragmentLayout.UI_DEBUG_PRINTS)
			Log.d("NotesListFragment", "onQueryTextChange: " + query);
		if (!currentQuery.equals(query)) {
			if (FragmentLayout.UI_DEBUG_PRINTS)
				Log.d("NotesListFragment", "this is a new query");
			currentQuery = query;

			getLoaderManager().restartLoader(0, null, this);

			// hide the clear completed option until search is over
			MenuItem clearCompleted = mOptionsMenu
					.findItem(R.id.menu_clearcompleted);
			if (clearCompleted != null) {
				// Only show this button if there is a list to create notes in
				if ("".equals(query)) {
					clearCompleted.setVisible(true);
				} else {
					clearCompleted.setVisible(false);
				}
			}
		}
		return true;
	}

	public boolean onQueryTextSubmit(String query) {
		// Just do what we do on text change
		return onQueryTextChange(query);
	}

	private void selectPos(int pos) {
		if (checkMode == CHECK_SINGLE_FUTURE) {
			setSingleCheck();
		}
		if (FragmentLayout.UI_DEBUG_PRINTS)
			Log.d(TAG, "selectPos: " + pos);
		getListView().setItemChecked(pos, true);
	}

	public void setSingleCheck() {
		if (FragmentLayout.UI_DEBUG_PRINTS)
			Log.d(TAG, "setSingleCheck");
		checkMode = CHECK_SINGLE;
		// ListView lv = getListView();
		if (FragmentLayout.LANDSCAPE_MODE) {
			// Fix the selection before releasing that
			lv.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
			// lv.setChoiceMode(ListView.CHOICE_MODE_NONE);
		} else {
			// Not nice to show selected item in list when no editor is showing
			lv.setChoiceMode(ListView.CHOICE_MODE_NONE);
		}
		lv.setLongClickable(true);
		// Make sure actiivty implements this
		lv.setOnItemLongClickListener((OnItemLongClickListener) activity);
	}

	public void setFutureSingleCheck() {
		// REsponsible for disabling the modal selector in the future.
		// can't do it now because it has to destroy itself etc...
		if (checkMode == CHECK_MULTI) {
			checkMode = CHECK_SINGLE_FUTURE;
			setSingleCheck();

			// Intent intent = new Intent(activity, FragmentLayout.class);

			// the mother activity will refresh the list for us
			// if (FragmentLayout.UI_DEBUG_PRINTS)
			// Log.d(TAG, "Launching intent: " + intent);
			// SingleTop, so will not launch a new instance
			// startActivity(intent);
		}
	}

	public void setRefreshActionItemState(boolean refreshing) {
		// On Honeycomb, we can set the state of the refresh button by giving it
		// a custom
		// action view.
		if (FragmentLayout.UI_DEBUG_PRINTS)
			Log.d(TAG, "setRefreshActionState");
		if (mOptionsMenu == null) {
			if (FragmentLayout.UI_DEBUG_PRINTS)
				Log.d(TAG, "setRefreshActionState: menu is null, returning");
			return;
		}

		final MenuItem refreshItem = mOptionsMenu.findItem(R.id.menu_sync);
		if (FragmentLayout.UI_DEBUG_PRINTS)
			Log.d(TAG, "setRefreshActionState: refreshItem not null? "
					+ Boolean.toString(refreshItem != null));
		if (refreshItem != null) {
			if (refreshing) {
				if (FragmentLayout.UI_DEBUG_PRINTS)
					Log.d(TAG,
							"setRefreshActionState: refreshing: "
									+ Boolean.toString(refreshing));
				if (mRefreshIndeterminateProgressView == null) {
					if (FragmentLayout.UI_DEBUG_PRINTS)
						Log.d(TAG,
								"setRefreshActionState: mRefreshIndeterminateProgressView was null, inflating one...");
					LayoutInflater inflater = (LayoutInflater) activity
							.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
					mRefreshIndeterminateProgressView = inflater.inflate(
							R.layout.actionbar_indeterminate_progress, null);
				}

				refreshItem.setActionView(mRefreshIndeterminateProgressView);
			} else {
				if (FragmentLayout.UI_DEBUG_PRINTS)
					Log.d(TAG, "setRefreshActionState: setting null actionview");
				refreshItem.setActionView(null);
			}
		}
	}

	

	public void setOnDeleteListener(OnEditorDeleteListener fragmentLayout) {
		this.onDeleteListener = fragmentLayout;
		if (modeCallback != null)
			modeCallback.setDeleteListener(this);
	}

	@Override
	public void onModalDelete(Collection<Integer> positions) {
		if (FragmentLayout.UI_DEBUG_PRINTS)
			Log.d(TAG, "onModalDelete");
		if (positions.contains(mCurCheckPosition)) {
			if (FragmentLayout.UI_DEBUG_PRINTS)
				Log.d(TAG, "onModalDelete contained setting id invalid");
			idInvalid = true;
		} else {
			// We must recalculate the positions index of the current note
			// This is always done when content changes
		}

		if (onDeleteListener != null) {
			HashSet<Long> ids = new HashSet<Long>();
			for (int pos : positions) {
				if (FragmentLayout.UI_DEBUG_PRINTS)
					Log.d(TAG, "onModalDelete pos: " + pos);
				ids.add(mAdapter.getItemId(pos));
			}
			onDeleteListener.onMultiDelete(ids, mCurId);
		}
	}

	public void showList(long id) {
		if (FragmentLayout.UI_DEBUG_PRINTS)
			Log.d(TAG, "showList id " + id);
		mCurListId = id;
		// Will create one if necessary
		Bundle args = new Bundle();
		if (FragmentLayout.LANDSCAPE_MODE)
			args.putBoolean(SHOULD_OPEN_NOTE, true);
		getLoaderManager().restartLoader(0, args, this);
	}

	private CursorLoader getAllNotesLoader() {
		// This is called when a new Loader needs to be created. This
		// sample only has one Loader, so we don't care about the ID.
		Uri baseUri = NotePad.Notes.CONTENT_URI;

		// Get current sort order or assemble the default one.
		String sortOrder = PreferenceManager.getDefaultSharedPreferences(
				activity).getString(NotesPreferenceFragment.KEY_SORT_TYPE,
				NotePad.Notes.DEFAULT_SORT_TYPE)
				+ " "
				+ PreferenceManager.getDefaultSharedPreferences(activity)
						.getString(NotesPreferenceFragment.KEY_SORT_ORDER,
								NotePad.Notes.DEFAULT_SORT_ORDERING);

		// Now create and return a CursorLoader that will take care of
		// creating a Cursor for the data being displayed.

		return new CursorLoader(activity, baseUri, PROJECTION, // Return
																// the note
																// ID and
																// title for
																// each
																// note.
				NotePad.Notes.COLUMN_NAME_DELETED + " IS NOT 1 AND "
						+ NotePad.Notes.COLUMN_NAME_HIDDEN + " IS NOT 1 AND "
						+ NotePad.Notes.COLUMN_NAME_LOCALHIDDEN
						+ " IS NOT 1 AND " + NotePad.Notes.COLUMN_NAME_LIST
						+ " IS " + mCurListId, // return
				// un-deleted
				// records.
				null, // No where clause, therefore no where column values.
				sortOrder);
	}

	private CursorLoader getSearchNotesLoader() {
		// This is called when a new Loader needs to be created. This
		// sample only has one Loader, so we don't care about the ID.
		Uri baseUri = NotePad.Notes.CONTENT_URI;
		// Now create and return a CursorLoader that will take care of
		// creating a Cursor for the data being displayed.

		// Get current sort order or assemble the default one.
		String sortOrder = PreferenceManager.getDefaultSharedPreferences(
				activity).getString(NotesPreferenceFragment.KEY_SORT_TYPE,
				NotePad.Notes.DEFAULT_SORT_TYPE)
				+ " "
				+ PreferenceManager.getDefaultSharedPreferences(activity)
						.getString(NotesPreferenceFragment.KEY_SORT_ORDER,
								NotePad.Notes.DEFAULT_SORT_ORDERING);

		// TODO include title field in search
		// I am not restricting the lists on purpose here. Search should be
		// global
		return new CursorLoader(activity, baseUri, PROJECTION,
				NotePad.Notes.COLUMN_NAME_DELETED + " IS NOT 1 AND "
						+ NotePad.Notes.COLUMN_NAME_HIDDEN + " IS NOT 1 AND "
						+ NotePad.Notes.COLUMN_NAME_LOCALHIDDEN
						+ " IS NOT 1 AND " + NotePad.Notes.COLUMN_NAME_NOTE
						+ " LIKE ?", new String[] { "%" + currentQuery + "%" }, // We
																				// don't
																				// care
																				// how
																				// it
																				// occurs
																				// in
																				// the
																				// note
				sortOrder);
	}

	@Override
	public Loader<Cursor> onCreateLoader(int id, Bundle args) {
		if (FragmentLayout.UI_DEBUG_PRINTS)
			Log.d(TAG, "onCreateLoader");

		if (args != null) {
			if (args.containsKey(SHOULD_OPEN_NOTE)
					&& args.getBoolean(SHOULD_OPEN_NOTE)) {
				autoOpenNote = true;
			}
		}
		if (currentQuery != null && !currentQuery.isEmpty()) {
			return getSearchNotesLoader();
		} else {
			return getAllNotesLoader();
		}
	}

	@Override
	public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
		// Swap the new cursor in. (The framework will take care of closing the
		// old cursor once we return.)
		if (FragmentLayout.UI_DEBUG_PRINTS)
			Log.d(TAG, "onLoadFinished");

		mAdapter.swapCursor(data);

		// The list should now be shown.
		if (isResumed()) {
			setListShown(true);
		} else {
			setListShownNoAnimation(true);
		}

		// Reselect current note in list, if possible
		// This happens in delete
		if (idInvalid) {
			idInvalid = false;
			// Note is invalid, so recalculate a valid position and index
			reCalculateValidValuesAfterDelete();
			reSelectId();
			if (FragmentLayout.LANDSCAPE_MODE)
				autoOpenNote = true;
		}

		// If a note was created, it will be set in this variable
		if (newNoteIdToOpen > -1) {
			showNote(getPosOfId(newNoteIdToOpen));
			newNoteIdToOpen = -1; // Should only be set to anything else on
									// create
		}
		// Open first note if this is first start
		// or if one was opened previously
		else if (autoOpenNote) {
			autoOpenNote = false;
			showFirstBestNote();
		} else {
			reSelectId();
		}
	}

	@Override
	public void onLoaderReset(Loader<Cursor> loader) {
		// This is called when the last Cursor provided to onLoadFinished()
		// above is about to be closed. We need to make sure we are no
		// longer using it.
		if (FragmentLayout.UI_DEBUG_PRINTS)
			Log.d(TAG, "onLoaderReset");
		mAdapter.swapCursor(null);
	}

	/**
	 * Re list notes when sorting changes
	 * 
	 */
	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
			String key) {
		try {
			if (activity.isFinishing()) {
				if (FragmentLayout.UI_DEBUG_PRINTS)
					Log.d(TAG, "isFinishing, should not update");
				// Setting the summary now would crash it with
				// IllegalStateException since we are not attached to a view
			} else {
				if (NotesPreferenceFragment.KEY_SORT_TYPE.equals(key)
						|| NotesPreferenceFragment.KEY_SORT_ORDER.equals(key)) {
					getLoaderManager().restartLoader(0, null, this);
				}
			}
		} catch (IllegalStateException e) {
			// This is just in case the "isFinishing" wouldn't be enough
			// The isFinishing will try to prevent us from doing something
			// stupid
			// This catch prevents the app from crashing if we do something
			// stupid
			if (FragmentLayout.UI_DEBUG_PRINTS)
				Log.d(TAG, "Exception was caught: " + e.getMessage());
		}
	}

	public void setCheckModeModal(boolean modal) {
		checkMode = modal ? CHECK_MULTI : CHECK_SINGLE;
	}
}
