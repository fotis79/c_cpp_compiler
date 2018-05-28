/*
 * Copyright 2018 Mr Duy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jecelyin.editor.v2.editor;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.core.widget.BaseEditorView;
import android.core.widget.EditAreaView;
import android.core.widget.model.EditorIndex;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import com.duy.astyle.AStyleInterface;
import com.duy.common.ShareUtil;
import com.duy.ide.editor.SimpleEditorActivity;
import com.duy.ide.editor.editor.R;
import com.duy.ide.editor.span.ErrorSpan;
import com.duy.ide.editor.theme.model.EditorTheme;
import com.duy.ide.editor.theme.model.SyntaxStyle;
import com.duy.ide.editor.view.EditorView;
import com.duy.ide.filemanager.SaveListener;
import com.jecelyin.common.utils.DLog;
import com.jecelyin.common.utils.UIUtils;
import com.jecelyin.editor.v2.Preferences;
import com.jecelyin.editor.v2.common.Command;
import com.jecelyin.editor.v2.dialog.DocumentInfoDialog;
import com.jecelyin.editor.v2.dialog.FinderDialog;

import org.gjt.sp.jedit.Catalog;
import org.gjt.sp.jedit.Mode;
import org.gjt.sp.jedit.awt.Font;
import org.gjt.sp.jedit.syntax.ModeProvider;
import org.gjt.sp.jedit.syntax.Token;

import java.io.File;
import java.util.Locale;

/**
 * @author Jecelyin Peng <jecelyin@gmail.com>
 */
public class EditorDelegate implements TextWatcher, IEditorDelegate {
    public final static String KEY_CLUSTER = "is_cluster";
    private static final String TAG = "EditorDelegate";
    EditAreaView mEditText;
    private Context mContext;
    private EditorView mEditorView;

    private Document mDocument;
    @NonNull
    private SavedState savedState;

    private int mOrientation;
    private boolean loaded = true;
    private int findResultsKeywordColor;

    public EditorDelegate(@NonNull SavedState ss) {
        savedState = ss;
    }

    public EditorDelegate(@NonNull File file, int offset, String encoding) {
        savedState = new SavedState();
        savedState.encoding = encoding;
        savedState.cursorOffset = offset;
        setCurrentFileToEdit(file);
    }

    private void setCurrentFileToEdit(File file) {
        savedState.file = file;
        savedState.title = savedState.file.getName();
    }

    void onLoadStart() {
        loaded = false;
        mEditText.setEnabled(false);
        mEditorView.setLoading(true);
    }

    void onLoadFinish() {
        mEditorView.setLoading(false);
        mEditText.setEnabled(true);
        mEditText.post(new Runnable() {
            @Override
            public void run() {
                if (savedState.cursorOffset < mEditText.getText().length())
                    mEditText.setSelection(savedState.cursorOffset);
            }
        });

        onDocumentChanged();
        loaded = true;
    }

    public Context getContext() {
        return mContext;
    }

    private SimpleEditorActivity getActivity() {
        return (SimpleEditorActivity) mContext;
    }

    public String getTitle() {
        return savedState.title;
    }

    public String getPath() {
        return mDocument == null ? savedState.file.getPath() : mDocument.getPath();
    }

    public String getEncoding() {
        return mDocument == null ? null : mDocument.getEncoding();
    }

    public String getText() {
        return mEditText.getText().toString();
    }

    public Editable getEditableText() {
        return mEditText.getText();
    }

    public EditAreaView getEditText() {
        return mEditText;
    }

    public void setEditorView(EditorView editorView) {
        mContext = editorView.getContext();
        mEditorView = editorView;
        mEditText = editorView.getEditText();
        mOrientation = mContext.getResources().getConfiguration().orientation;

        TypedArray a = mContext.obtainStyledAttributes(new int[]{R.attr.findResultsKeyword});
        findResultsKeywordColor = a.getColor(0, Color.BLACK);
        a.recycle();

        mDocument = new Document(mContext, this, savedState.file);
        mEditText.setReadOnly(Preferences.getInstance(mContext).isReadOnly());
        mEditText.setCustomSelectionActionModeCallback(new EditorSelectionActionModeCallback());

        if (savedState.editorState != null) {
            mDocument.onRestoreInstanceState(savedState);
            mEditText.onRestoreInstanceState(savedState.editorState);
        } else {
            mDocument.loadFile(savedState.file, savedState.encoding);
        }

        mEditText.addTextChangedListener(this);
        onDocumentChanged();
    }

    public void onDestroy() {
        mEditText.removeTextChangedListener(mDocument);
        mEditText.removeTextChangedListener(this);
    }

    public CharSequence getSelectedText() {
        return mEditText.hasSelection() ? mEditText.getEditableText().subSequence(mEditText.getSelectionStart(), mEditText.getSelectionEnd()) : "";
    }

    public boolean isChanged() {
        return mDocument != null && mDocument.isChanged();
    }

    public CharSequence getToolbarText() {
        String encode = mDocument == null ? "UTF-8" : mDocument.getEncoding();
        String fileMode = mDocument == null || mDocument.getModeName() == null ? "" : mDocument.getModeName();
        String title = getTitle();
        String changed = isChanged() ? "*" : "";
        String cursor = "";
        if (mEditText != null && mEditText.getLayout() != null && getCursorOffset() >= 0) {
            int cursorOffset = getCursorOffset();
            int line = mEditText.getLayout().getLineForOffset(cursorOffset);
            cursor += line + ":" + cursorOffset;
        }
        return String.format(Locale.US, "%s%s  \t|\t  %s \t %s \t %s", changed, title, encode, fileMode, cursor);
    }

    private void startSaveFileSelectorActivity() {
        if (mDocument != null) {
            getActivity().startPickPathActivity(mDocument.getPath(), mDocument.getEncoding());
        }
    }

    /**
     * Write out content of editor to file in background thread
     *
     * @param file     - File to write
     * @param encoding - file encoding
     */
    public void saveInBackground(File file, String encoding) {
        if (mDocument != null) {
            mDocument.saveInBackground(file, encoding == null ? mDocument.getEncoding() : encoding,
                    new SaveListener() {
                        @Override
                        public void onSavedSuccess() {
                            onDocumentChanged();
                        }

                        @Override
                        public void onSaveFailed(Exception e) {
                            UIUtils.alert(mContext, e.getMessage());
                        }
                    });
        }
    }

    /**
     * Write current content of editor to file
     */
    @Override
    public void saveCurrentFile() throws Exception {
        if (mDocument.isChanged()) {
            mDocument.writeToFile(mDocument.getFile(), mDocument.getEncoding());
        }
    }

    /**
     * Write out content of editor to file in background thread
     */
    @Override
    public void saveInBackground() {
        if (mDocument.isChanged()) {
            saveInBackground(mDocument.getFile(), mDocument.getEncoding());
        } else {
            if (DLog.DEBUG) DLog.d(TAG, "saveInBackground: document not changed, no need to save");
        }
    }

    public void addHighlight(int start, int end) {
        mEditText.getText().setSpan(new BackgroundColorSpan(findResultsKeywordColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        mEditText.setSelection(end, end);
    }

    public int getCursorOffset() {
        if (mEditText == null) {
            return -1;
        }
        return mEditText.getSelectionEnd();
    }

    @Override
    public void doCommand(Command command) {
        if (mEditText == null)
            return;
        boolean readonly = Preferences.getInstance(mContext).isReadOnly();
        switch (command.what) {
            case HIDE_SOFT_INPUT:
                mEditText.hideSoftInput();
                break;
            case SHOW_SOFT_INPUT:
                mEditText.showSoftInput();
                break;
            case UNDO:
                if (!readonly)
                    mEditText.undo();
                break;
            case REDO:
                if (!readonly)
                    mEditText.redo();
                break;
            case CUT:
                if (!readonly) {
                    mEditText.cut();
                    return;
                }
            case COPY:
                mEditText.copy();
                return;
            case PASTE:
                if (!readonly) {
                    mEditText.paste();
                    return;
                }
            case SELECT_ALL:
                mEditText.selectAll();
                return;
            case DUPLICATION:
                if (!readonly)
                    mEditText.duplication();
                break;
            case CONVERT_WRAP_CHAR:
                if (!readonly)
                    mEditText.convertWrapCharTo((String) command.object);
                break;
            case GOTO_INDEX:
                int col = command.args.getInt("col", -1);
                int line = command.args.getInt("line", -1);
                goToLine(line, col);
                break;
            case GOTO_TOP:
                mEditText.gotoTop();
                break;
            case GOTO_END:
                mEditText.gotoEnd();
                break;
            case DOC_INFO:
                DocumentInfoDialog documentInfoDialog = new DocumentInfoDialog(mContext);
                documentInfoDialog.setDocument(mDocument);
                documentInfoDialog.setEditAreaView(mEditText);
                documentInfoDialog.setPath(mDocument.getPath());
                documentInfoDialog.show();
                break;
            case READONLY_MODE:
                Preferences preferences = Preferences.getInstance(mContext);
                boolean readOnly = preferences.isReadOnly();
                mEditText.setReadOnly(readOnly);
                break;
            case SAVE:
                if (!readonly) {
                    saveInBackground();
                }
                break;
            case SAVE_AS:
                startSaveFileSelectorActivity();
                break;
            case FIND:
                FinderDialog.showFindDialog(this);
                break;
            case HIGHLIGHT:
                String scope = (String) command.object;
                if (scope == null) {
                    Mode mode;
                    String firstLine = getEditableText().subSequence(0, Math.min(80, getEditableText().length())).toString();
                    if (TextUtils.isEmpty(mDocument.getPath()) || TextUtils.isEmpty(firstLine)) {
                        mode = ModeProvider.instance.getMode(Catalog.DEFAULT_MODE_NAME);
                    } else {
                        mode = ModeProvider.instance.getModeForFile(mDocument.getPath(), null, firstLine);
                    }

                    if (mode == null) {
                        mode = ModeProvider.instance.getMode(Catalog.DEFAULT_MODE_NAME);
                    }

                    scope = mode.getName();
                }
                mDocument.setMode(scope);
                break;
            case INSERT_TEXT:
                if (!readonly) {
                    int selStart = mEditText.getSelectionStart();
                    int selEnd = mEditText.getSelectionEnd();
                    if (selStart == -1 || selEnd == -1) {
                        mEditText.getText().insert(0, (CharSequence) command.object);
                    } else {
                        mEditText.getText().replace(selStart, selEnd, (CharSequence) command.object);
                    }
                }
                break;
            case RELOAD_WITH_ENCODING:
                reOpenWithEncoding((String) command.object);
                break;
            case CURSOR_FORWARD:
                mEditText.forwardLocation();
                break;
            case CURSOR_BACK:
                mEditText.backLocation();
                break;
            case REQUEST_FOCUS:
                mEditText.requestFocus();
                break;
            case HIGHLIGHT_ERROR:
                highlightError(command.args, false);
                break;
            case CLEAR_ERROR:
                clearErrorSpan();
                break;
            case SHARE_CODE:
                shareCurrentContent();
                break;
            case FORMAT_SOURCE:
                formatSource();
                break;
        }
    }

    /**
     * Format current source
     * Supported: c++, c, java,
     */
    private void formatSource() {
        String currMode = mDocument.getModeName();
        String mode;
        String style = "gnu";
        if (currMode.contentEquals(Catalog.getModeByName("C++").getName())) {
            mode = "c";
        } else if (currMode.contentEquals(Catalog.getModeByName("C").getName())) {
            mode = "c";
        } else if (currMode.contentEquals(Catalog.getModeByName("Objective-C").getName())) {
            mode = "c";
        } else if (currMode.contentEquals(Catalog.getModeByName("C#").getName())) {
            mode = "cs";
        } else if (currMode.contentEquals(Catalog.getModeByName("Java").getName())) {
            mode = "java";
            style = "java";
        } else {
            Toast.makeText(mContext, R.string.unsupported_format_source, Toast.LENGTH_SHORT).show();
            return;
        }

        String options = "--mode=" + mode;
        if (!style.isEmpty()) {
            options += " --style=" + style;
        }
        try {
            AStyleInterface astyle = new AStyleInterface();
            String source = mEditText.getText().toString();
            int oldSelection = mEditText.getSelectionStart();
            String formatted = astyle.formatSource(source, options);
            mEditText.setText(new SpannableStringBuilder(formatted));
            mEditText.setSelection(oldSelection);
            Toast.makeText(mContext, R.string.formated_source, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(mContext, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void shareCurrentContent() {
        ShareUtil.shareText(mContext, mEditText.getText().toString());
    }

    /**
     * Move cursor to [realLine: column]
     *
     * @param realLine - real line in source code, note that realLine start at 1
     * @param column   - current column, if col < 0, cursor is start at realLine
     */
    private void goToLine(int realLine, int column) {
        EditorIndex index = mEditText.getCursorIndex(realLine, column);
        if (index != null) {
            mEditText.setSelection(index.offset);
            mEditText.scrollToLine(index.line - 2);
        }
    }

    private void clearErrorSpan() {
        Editable editableText = mEditText.getEditableText();
        ErrorSpan[] spans = editableText.getSpans(0, mEditText.length(), ErrorSpan.class);
        for (ErrorSpan span : spans) {
            editableText.removeSpan(span);
        }
    }

    /**
     * Set {@link com.duy.ide.editor.span.ErrorSpan} from line:col to lineEnd:colEnd
     * If it hasn't end index, this method will be set span for all line
     *
     * @param args - contains four key
     */
    private void highlightError(Bundle args, boolean includeWhitespace) {
        if (DLog.DEBUG) DLog.d(TAG, "highlightError() called with: args = [" + args + "]");
        int realLine = args.getInt("line", -1);
        int virtualLine = mEditText.realLineToVirtualLine(realLine);
        if (virtualLine != -1) { //found
            Editable editableText = mEditText.getEditableText();
            int startIndex;
            int endIndex;
            if (args.containsKey("lineEnd")) {
                int lineEnd = args.getInt("lineEnd");
                int colEnd = args.getInt("colEnd", 1);
                int colStart = args.getInt("col", 1);
                startIndex = mEditText.getCursorIndex(realLine, colStart).offset;
                endIndex = mEditText.getCursorIndex(lineEnd, colEnd).offset;

            } else {
                startIndex = mEditText.getLayout().getLineStart(virtualLine);
                endIndex = mEditText.getLayout().getLineEnd(virtualLine);
                //remove white space, tab or line terminate
                if (!includeWhitespace) {
                    while (startIndex < endIndex) {
                        if (Character.isWhitespace(editableText.charAt(startIndex))) {
                            startIndex++;
                            continue;
                        }
                        if (Character.isWhitespace(editableText.charAt(endIndex - 1))) {
                            endIndex--;
                            continue;
                        }
                        break;
                    }
                }
            }
            ErrorSpan[] spans = editableText.getSpans(startIndex, endIndex, ErrorSpan.class);
            for (ErrorSpan span : spans) {
                editableText.removeSpan(span);
            }
            if (startIndex < endIndex) {
                EditorTheme editorTheme = mEditText.getEditorTheme();
                SyntaxStyle color = editorTheme.getSyntaxStyles()[Token.INVALID];
                Font font = color.getFont();
                if (font != null) {
                    editableText.setSpan(new StyleSpan(font.getStyle()), startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                editableText.setSpan(new ErrorSpan(color.getForegroundColor()), startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private void reOpenWithEncoding(final String encoding) {
        final File file = mDocument.getFile();
        if (mDocument.isChanged()) {
            new AlertDialog.Builder(mContext)
                    .setTitle(R.string.document_changed)
                    .setMessage(R.string.give_up_document_changed_message)
                    .setPositiveButton(R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.cancel();
                        }
                    })
                    .setNegativeButton(R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.cancel();
                            mDocument.loadFile(file, encoding);
                        }
                    })
                    .create()
                    .show();
            return;
        }
        mDocument.loadFile(file, encoding);
    }

    /**
     * This method will be called when document changed file
     */
    @MainThread
    public void onDocumentChanged() {
        setCurrentFileToEdit(mDocument.getFile());
        getActivity().invalidateEditMenu(mDocument, mEditText);
        getActivity().getTabManager().onDocumentChanged(mDocument);
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {

    }

    @Override
    public void afterTextChanged(Editable s) {
        if (loaded) {
            getActivity().invalidateEditMenu(mDocument, mEditText);
            getActivity().getTabManager().onDocumentChanged(mDocument);
        }
    }

    @Nullable
    public String getLang() {
        if (mDocument == null) {
            return null;
        }
        return mDocument.getModeName();
    }

    private void convertSelectedText(int id) {
        if (mEditText == null || !mEditText.hasSelection()) {
            return;
        }

        int start = mEditText.getSelectionStart();
        int end = mEditText.getSelectionEnd();

        String selectedText = getEditableText().subSequence(start, end).toString();

        if (id == R.id.m_convert_to_uppercase) {
            selectedText = selectedText.toUpperCase();

        } else if (id == R.id.m_convert_to_lowercase) {
            selectedText = selectedText.toLowerCase();

        }
        getEditableText().replace(start, end, selectedText);
    }

    Parcelable onSaveInstanceState() {
        if (mDocument != null) {
            mDocument.onSaveInstanceState(savedState);
        }
        if (mEditText != null) {
            mEditText.setFreezesText(true);
            savedState.editorState = (BaseEditorView.SavedState) mEditText.onSaveInstanceState();
        }

        if (loaded && mDocument != null) {
            if (Preferences.getInstance(mContext).isAutoSave()) {
                int newOrientation = mContext.getResources().getConfiguration().orientation;
                if (mOrientation != newOrientation) {
                    DLog.d("current is screen orientation, discard auto save!");
                    mOrientation = newOrientation;
                } else {
                    try {
                        saveCurrentFile();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        return savedState;
    }

    @Override
    public Document getDocument() {
        return mDocument;
    }

    public static class SavedState implements Parcelable {
        public static final Creator<SavedState> CREATOR = new Creator<SavedState>() {
            @Override
            public SavedState createFromParcel(Parcel source) {
                return new SavedState(source);
            }

            @Override
            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
        int cursorOffset;
        int lineNumber;
        File file;
        String title;
        String encoding;
        String modeName;
        BaseEditorView.SavedState editorState;
        byte[] textMd5;
        int textLength;

        SavedState() {
        }

        SavedState(Parcel in) {
            this.cursorOffset = in.readInt();
            this.lineNumber = in.readInt();
            String file = in.readString();
            this.file = new File(file);
            this.title = in.readString();
            this.encoding = in.readString();
            this.modeName = in.readString();
            int hasState = in.readInt();
            if (hasState == 1) {
                this.editorState = in.readParcelable(BaseEditorView.SavedState.class.getClassLoader());
            }
            this.textMd5 = in.createByteArray();
            this.textLength = in.readInt();
        }


        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(this.cursorOffset);
            dest.writeInt(this.lineNumber);
            dest.writeString(this.file.getPath());
            dest.writeString(this.title);
            dest.writeString(this.encoding);
            dest.writeString(this.modeName);
            dest.writeInt(this.editorState == null ? 0 : 1);
            if (this.editorState != null) {
                dest.writeParcelable(this.editorState, flags);
            }
            dest.writeByteArray(this.textMd5);
            dest.writeInt(textLength);
        }
    }

    private class EditorSelectionActionModeCallback implements ActionMode.Callback {

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            final TypedArray arr = mContext.obtainStyledAttributes(
                    R.styleable.SelectionModeDrawables);

            boolean readOnly = Preferences.getInstance(mContext).isReadOnly();
            boolean selected = mEditText.hasSelection();
            if (selected) {
                menu.add(0, R.id.action_find_replace, 0, R.string.find).
                        setIcon(R.drawable.ic_find_replace_white_24dp).
                        setAlphabeticShortcut('f').
                        setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);

                if (!readOnly) {
                    menu.add(0, R.id.m_convert_to_uppercase, 0, R.string.convert_to_uppercase)
                            .setIcon(R.drawable.m_uppercase)
                            .setAlphabeticShortcut('U')
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);

                    menu.add(0, R.id.m_convert_to_lowercase, 0, R.string.convert_to_lowercase)
                            .setIcon(R.drawable.m_lowercase)
                            .setAlphabeticShortcut('L')
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
                }
            }

            if (!readOnly) {
                menu.add(0, R.id.m_duplication, 0, selected ? R.string.duplication_text : R.string.duplication_line)
                        .setIcon(R.drawable.ic_control_point_duplicate_white_24dp)
                        .setAlphabeticShortcut('L')
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
            }

            arr.recycle();
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            int i = item.getItemId();
            if (i == R.id.action_find_replace) {
                doCommand(new Command(Command.CommandEnum.FIND));
                return true;
            } else if (i == R.id.m_convert_to_uppercase || i == R.id.m_convert_to_lowercase) {
                convertSelectedText(item.getItemId());
                return true;
            } else if (i == R.id.m_duplication) {
                mEditText.duplication();
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {

        }
    }

}
