/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.editor.impl.view;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.bidi.BidiRegionsSeparator;
import com.intellij.openapi.editor.bidi.LanguageBidiRegionsSeparator;
import com.intellij.openapi.editor.colors.FontPreferences;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.editor.impl.ComplementaryFontsRegistry;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.editor.impl.FontInfo;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.text.CharArrayUtil;
import org.intellij.lang.annotations.JdkConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.text.Bidi;
import java.util.*;
import java.util.List;

/**
 * Layout of a single line of document text. Consists of a series of BidiRuns, which, in turn, consist of TextFragments.
 * TextFragments within BidiRun are grouped into Chunks for performance reasons, glyph layout is performed per-Chunk, and only 
 * for required Chunks.
 */
abstract class LineLayout {
  private LineLayout() {}
  
  /**
   * Creates a layout for a fragment of text from editor.
   */
  @NotNull
  static LineLayout create(@NotNull EditorView view, int line, boolean skipBidiLayout) {
    List<BidiRun> runs = createFragments(view, line, skipBidiLayout);
    return createLayout(runs);
  }

  /**
   * Creates a layout for an arbitrary piece of text (using a common font style).
   */
  @NotNull
  static LineLayout create(@NotNull EditorView view, @NotNull CharSequence text, @JdkConstants.FontStyle int fontStyle) {
    List<BidiRun> runs = createFragments(view, text, fontStyle);
    LineLayout delegate = createLayout(runs);
    return new WithSize(delegate);
  }
  
  private static LineLayout createLayout(@NotNull List<BidiRun> runs) {
    if (runs.isEmpty()) return new SingleChunk(null);
    if (runs.size() == 1) {
      BidiRun run = runs.get(0);
      if (run.level == 0 && run.getChunkCount() == 1) {
        Chunk chunk = run.chunks == null ? new Chunk(0, run.endOffset) : run.chunks[0];
        return new SingleChunk(chunk);
      }
    }
    return new MultiChunk(runs.toArray(new BidiRun[runs.size()]));
  }

  // runs are supposed to be in logical order initially
  private static void reorderRunsVisually(BidiRun[] bidiRuns) {
    byte[] levels = new byte[bidiRuns.length];
    for (int i = 0; i < bidiRuns.length; i++) {
      levels[i] = bidiRuns[i].level;
    }
    Bidi.reorderVisually(levels, 0, bidiRuns, 0, levels.length);
  }

  static boolean isBidiLayoutRequired(@NotNull CharSequence text) {
    char[] chars = CharArrayUtil.fromSequence(text);
    return Bidi.requiresBidi(chars, 0, chars.length);
  }
  
  private static List<BidiRun> createFragments(@NotNull EditorView view, int line, boolean skipBidiLayout) {
    EditorImpl editor = view.getEditor();
    Document document = editor.getDocument();
    int lineStartOffset = document.getLineStartOffset(line);
    int lineEndOffset = document.getLineEndOffset(line);
    if (lineEndOffset <= lineStartOffset) return Collections.emptyList();
    if (skipBidiLayout) return Collections.singletonList(new BidiRun(lineEndOffset - lineStartOffset));
    CharSequence text = document.getImmutableCharSequence().subSequence(lineStartOffset, lineEndOffset);
    char[] chars = CharArrayUtil.fromSequence(text);
    return createRuns(editor, chars, lineStartOffset);
  }

  private static List<BidiRun> createFragments(@NotNull EditorView view, @NotNull CharSequence text, 
                                                @JdkConstants.FontStyle int fontStyle) {
    if (text.length() == 0) return Collections.emptyList();
    EditorImpl editor = view.getEditor();
    FontRenderContext fontRenderContext = view.getFontRenderContext();
    FontPreferences fontPreferences = editor.getColorsScheme().getFontPreferences();
    char[] chars = CharArrayUtil.fromSequence(text);
    List<BidiRun> runs = createRuns(editor, chars, -1);
    for (BidiRun run : runs) {
      for (Chunk chunk : run.getChunks()) {
        addFragments(run, chunk, chars, chunk.startOffset, chunk.endOffset, fontStyle, fontPreferences, fontRenderContext, null);
      }
    }
    return runs;
  }

  private static List<BidiRun> createRuns(EditorImpl editor, char[] text, int startOffsetInEditor) {
    int textLength = text.length;
    if (editor.myDisableRtl || !Bidi.requiresBidi(text, 0, textLength)) {
      return Collections.singletonList(new BidiRun(textLength));
    }
    List<BidiRun> runs = new ArrayList<BidiRun>();
    if (startOffsetInEditor >= 0) {
      // running bidi algorithm separately for text fragments corresponding to different lexer tokens
      int lastOffset = startOffsetInEditor;
      IElementType lastToken = null;
      HighlighterIterator iterator = editor.getHighlighter().createIterator(startOffsetInEditor);
      int endOffsetInEditor = startOffsetInEditor + textLength;
      while (!iterator.atEnd() && iterator.getStart() < endOffsetInEditor) {
        IElementType currentToken = iterator.getTokenType();
        if (distinctTokens(lastToken, currentToken)) {
          int tokenStart = Math.max(iterator.getStart(), startOffsetInEditor);
          addRuns(runs, text, lastOffset - startOffsetInEditor, tokenStart - startOffsetInEditor);
          lastToken = currentToken;
          lastOffset = tokenStart;
        }
        iterator.advance();
      }
      addRuns(runs, text, lastOffset - startOffsetInEditor, endOffsetInEditor - startOffsetInEditor);
    }
    else {
      addRuns(runs, text, 0, textLength);
    }
    return runs;
  }

  private static boolean distinctTokens(@Nullable IElementType token1, @Nullable IElementType token2) {
    if (token1 == token2) return false;
    if (token1 == null || token2 == null) return true;
    if (!token1.getLanguage().is(token2.getLanguage())) return true;
    BidiRegionsSeparator separator = LanguageBidiRegionsSeparator.INSTANCE.forLanguage(token1.getLanguage());
    return separator.createBorderBetweenTokens(token1, token2);
  }
  
  private static void addRuns(List<BidiRun> runs, char[] text, int start, int end) {
    int afterLastTabPosition = start;
    for (int i = start; i < end; i++) {
      if (text[i] == '\t') {
        addRunsNoTabs(runs, text, afterLastTabPosition, i);
        afterLastTabPosition = i + 1;
        addOrMergeRun(runs, new BidiRun((byte)0, i, i + 1));
      }
    }
    addRunsNoTabs(runs, text, afterLastTabPosition, end);
  }

  private static void addRunsNoTabs(List<BidiRun> runs, char[] text, int start, int end) {
    if (start >= end) return;
    Bidi bidi = new Bidi(text, start, null, 0, end - start, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
    int runCount = bidi.getRunCount();
    for (int i = 0; i < runCount; i++) {
      addOrMergeRun(runs, new BidiRun((byte)bidi.getRunLevel(i), start + bidi.getRunStart(i), start + bidi.getRunLimit(i)));
    }
  }

  private static void addOrMergeRun(List<BidiRun> runs, BidiRun run) {
    int size = runs.size();
    if (size > 0 && runs.get(size - 1).level == 0 && run.level == 0) {
      BidiRun lastRun = runs.remove(size - 1);
      assert lastRun.endOffset == run.startOffset;
      runs.add(new BidiRun((byte)0, lastRun.startOffset, run.endOffset));
    }
    else {
      runs.add(run);
    }
  }
  
  @SuppressWarnings("AssignmentToForLoopParameter")
  private static void addFragments(BidiRun run, Chunk chunk, char[] text, int start, int end, int fontStyle,
                                   FontPreferences fontPreferences, FontRenderContext fontRenderContext,
                                   @Nullable TabFragment tabFragment) {
    assert start < end;
    FontInfo currentFontInfo = null;
    int currentIndex = start;
    for(int i = start; i < end; i++) {
      char c = text[i];
      if (c == '\t' && tabFragment != null) {
        assert run.level == 0;
        addTextFragmentIfNeeded(chunk, text, currentIndex, i, currentFontInfo, fontRenderContext, false);
        chunk.fragments.add(tabFragment);
        currentFontInfo = null;
        currentIndex = i + 1;
      }
      else {
        boolean surrogatePair = false;
        int codePoint = c;
        if (Character.isHighSurrogate(c) && (i + 1 < end)) {
          char nextChar = text[i + 1];
          if (Character.isLowSurrogate(nextChar)) {
            codePoint = Character.toCodePoint(c, nextChar);
            surrogatePair = true;
          }
        }
        FontInfo fontInfo = ComplementaryFontsRegistry.getFontAbleToDisplay(codePoint, fontStyle, fontPreferences);
        if (!fontInfo.equals(currentFontInfo)) {
          addTextFragmentIfNeeded(chunk, text, currentIndex, i, currentFontInfo, fontRenderContext, run.isRtl());
          currentFontInfo = fontInfo;
          currentIndex = i;
        }
        if (surrogatePair) i++;
      }
    }
    addTextFragmentIfNeeded(chunk, text, currentIndex, end, currentFontInfo, fontRenderContext, run.isRtl());
    assert !chunk.fragments.isEmpty();
  }
  
  private static void addTextFragmentIfNeeded(Chunk chunk, char[] chars, int from, int to, FontInfo fontInfo, 
                                              FontRenderContext fontRenderContext, boolean isRtl) {
    if (to > from) {
      assert fontInfo != null;
      chunk.fragments.add(TextFragmentFactory.createTextFragment(chars, from, to, isRtl, fontInfo, fontRenderContext));
    }
  }
  
  Iterable<VisualFragment> getFragmentsInVisualOrder(final float startX) {
    return new Iterable<VisualFragment>() {
      @Override
      public Iterator<VisualFragment> iterator() {
        return new VisualOrderIterator(null, 0, startX, 0, 0, 0, getRunsInVisualOrder());
      }
    };
  }

  /**
   * If <code>quickEvaluationListener</code> is provided, quick approximate iteration becomes enabled, listener will be invoked
   * if approximation will in fact be used during width calculation.
   */
  Iterator<VisualFragment> getFragmentsInVisualOrder(@NotNull final EditorView view,
                                                     final int line,
                                                     final float startX,
                                                     final int startVisualColumn,
                                                     final int startOffset,
                                                     int endOffset,
                                                     @Nullable Runnable quickEvaluationListener) {
    assert startOffset <= endOffset;
    final BidiRun[] runs;
    if (startOffset == endOffset) {
      runs = new BidiRun[0];
    }
    else {
      List<BidiRun> runList = new ArrayList<BidiRun>();
      for (BidiRun run : getRunsInLogicalOrder()) {
        if (run.endOffset <= startOffset) continue;
        if (run.startOffset >= endOffset) break;
        runList.add(run.subRun(view, line, startOffset, endOffset, quickEvaluationListener));
      }
      runs = runList.toArray(new BidiRun[runList.size()]);
      if (runs.length > 1) {
        reorderRunsVisually(runs);
      }
    }
    final int startLogicalColumn = view.getLogicalPositionCache().offsetToLogicalColumn(line, startOffset);
    return new VisualOrderIterator(view, line, startX, startVisualColumn, startLogicalColumn, startOffset, runs);
  }

  float getWidth() {
    throw new RuntimeException("This LineLayout instance doesn't have precalculated width");
  }

  abstract boolean isLtr();
  
  abstract boolean isRtlLocation(int offset, boolean leanForward);

  abstract int findNearestDirectionBoundary(int offset, boolean lookForward);
  
  abstract BidiRun[] getRunsInLogicalOrder();

  abstract BidiRun[] getRunsInVisualOrder();
  
  private static class SingleChunk extends LineLayout {
    private final Chunk myChunk;

    private SingleChunk(Chunk chunk) {
      myChunk = chunk;
    }

    @Override
    boolean isLtr() {
      return true;
    }
    
    @Override
    boolean isRtlLocation(int offset, boolean leanForward) {
      return false;
    }

    @Override
    int findNearestDirectionBoundary(int offset, boolean lookForward) {
      return -1;
    }

    @Override
    BidiRun[] getRunsInLogicalOrder() {
      return createRuns();
    }

    @Override
    BidiRun[] getRunsInVisualOrder() {
      return createRuns();
    }
    
    private BidiRun[] createRuns() {
      if (myChunk == null) return new BidiRun[0];
      BidiRun run = new BidiRun(myChunk.endOffset);
      run.chunks = new Chunk[] {myChunk};
      return new BidiRun[] {run};
    }
  }
  
  private static class MultiChunk extends LineLayout {
    private final BidiRun[] myBidiRunsInLogicalOrder;
    private final BidiRun[] myBidiRunsInVisualOrder;

    private MultiChunk(BidiRun[] bidiRunsInLogicalOrder) {
      myBidiRunsInLogicalOrder = bidiRunsInLogicalOrder;
      if (bidiRunsInLogicalOrder.length > 1) {
        myBidiRunsInVisualOrder = myBidiRunsInLogicalOrder.clone();
        reorderRunsVisually(myBidiRunsInVisualOrder);
      }
      else {
        myBidiRunsInVisualOrder = bidiRunsInLogicalOrder;
      }
    }

    @Override
    boolean isLtr() {
      return myBidiRunsInLogicalOrder.length == 0 || myBidiRunsInLogicalOrder.length == 1 && !myBidiRunsInLogicalOrder[0].isRtl();
    }

    @Override
    boolean isRtlLocation(int offset, boolean leanForward) {
      if (offset == 0 && !leanForward) return false;
      for (BidiRun run : myBidiRunsInLogicalOrder) {
        if (offset < run.endOffset || offset == run.endOffset && !leanForward) return run.isRtl();
      }
      return false;
    }
    
    @Override
    int findNearestDirectionBoundary(int offset, boolean lookForward) {
      if (lookForward) {
        byte originLevel = -1;
        for (BidiRun run : myBidiRunsInLogicalOrder) {
          if (originLevel >= 0) {
            if (run.level != originLevel) return run.startOffset;
          }
          else if (run.endOffset > offset) {
            originLevel = run.level;
          }
        }
        return originLevel > 0 ? myBidiRunsInLogicalOrder[myBidiRunsInLogicalOrder.length - 1].endOffset : -1;
      }
      else {
        byte originLevel = -1;
        for (int i = myBidiRunsInLogicalOrder.length - 1; i >= 0; i--) {
          BidiRun run = myBidiRunsInLogicalOrder[i];
          if (originLevel >= 0) {
            if (run.level != originLevel) return run.endOffset;
          }
          else if (run.startOffset < offset) {
            originLevel = run.level;

          }
        }
        return originLevel > 0 ? 0 : -1;
      }
    }

    @Override
    BidiRun[] getRunsInLogicalOrder() {
      return myBidiRunsInLogicalOrder;
    }

    @Override
    BidiRun[] getRunsInVisualOrder() {
      return myBidiRunsInVisualOrder;
    }
  }
  
  private static class WithSize extends LineLayout {
    private final LineLayout myDelegate;
    private final float myWidth;
    
    private WithSize(@NotNull LineLayout delegate) {
      myDelegate = delegate;
      myWidth = calculateWidth();
    }

    private float calculateWidth() {
      float x = 0;
      for (VisualFragment fragment : getFragmentsInVisualOrder(x)) {
        x = fragment.getEndX();
      }
      return x;
    }

    @Override
    float getWidth() {
      return myWidth;
    }

    @Override
    boolean isLtr() {
      return myDelegate.isLtr();
    }

    @Override
    boolean isRtlLocation(int offset, boolean leanForward) {
      return myDelegate.isRtlLocation(offset, leanForward);
    }

    @Override
    int findNearestDirectionBoundary(int offset, boolean lookForward) {
      return myDelegate.findNearestDirectionBoundary(offset, lookForward);
    }

    @Override
    BidiRun[] getRunsInLogicalOrder() {
      return myDelegate.getRunsInLogicalOrder();
    }

    @Override
    BidiRun[] getRunsInVisualOrder() {
      return myDelegate.getRunsInVisualOrder();
    }
  }

  private static class BidiRun {
    private static final int CHUNK_CHARACTERS = 1024;
    
    private final byte level;
    private final int startOffset;
    private final int endOffset;
    private Chunk[] chunks; // in logical order

    private BidiRun(int length) {
      this((byte)0, 0, length);
    }
    
    private BidiRun(byte level, int startOffset, int endOffset) {
      this.level = level;
      this.startOffset = startOffset;
      this.endOffset = endOffset;
    }
    
    private boolean isRtl() {
      return (level & 1) != 0;
    }
    
    private Chunk[] getChunks() {
      if (chunks == null) {
        int chunkCount = getChunkCount();
        chunks = new Chunk[chunkCount];
        for (int i = 0; i < chunkCount; i++) {
          int from = startOffset + i * CHUNK_CHARACTERS;
          int to = (i == chunkCount - 1) ? endOffset : from + CHUNK_CHARACTERS;
          Chunk chunk = new Chunk(from, to);
          chunks[i] = chunk;
        }
      }
      return chunks;
    }

    private int getChunkCount() {
      return (endOffset - startOffset + CHUNK_CHARACTERS - 1) / CHUNK_CHARACTERS;
    }

    private BidiRun subRun(@NotNull EditorView view, int line, int targetStartOffset, int targetEndOffset, 
                           @Nullable Runnable quickEvaluationListener) {
      assert targetStartOffset < endOffset;
      assert targetEndOffset > startOffset;
      int start = Math.max(startOffset, targetStartOffset);
      int end = Math.min(endOffset, targetEndOffset);
      BidiRun subRun = new BidiRun(level, start, end);
      List<Chunk> subChunks = new ArrayList<Chunk>();
      for (Chunk chunk : getChunks()) {
        if (chunk.endOffset <= start) continue;
        if (chunk.startOffset >= end) break;
        subChunks.add(chunk.subChunk(view, this, line, start, end, quickEvaluationListener));
      }
      subRun.chunks = subChunks.toArray(new Chunk[subChunks.size()]);
      return subRun;
    }
  }
  
  static class Chunk {
    final List<LineFragment> fragments = new ArrayList<LineFragment>(); // in logical order
    private int startOffset;
    private int endOffset;

    private Chunk(int startOffset, int endOffset) {
      this.startOffset = startOffset;
      this.endOffset = endOffset;
    }

    private void ensureLayout(@NotNull EditorView view, BidiRun run, int line) {
      if (isReal()) {
        view.getTextLayoutCache().onChunkAccess(this);
      }
      if (!fragments.isEmpty()) return;
      int lineStartOffset = view.getEditor().getDocument().getLineStartOffset(line);
      int start = lineStartOffset + startOffset;
      int end = lineStartOffset + endOffset;
      IterationState it = new IterationState(view.getEditor(), start, end, false, false, true, false, false);
      FontPreferences fontPreferences = view.getEditor().getColorsScheme().getFontPreferences();
      char[] chars = CharArrayUtil.fromSequence(view.getEditor().getDocument().getImmutableCharSequence(), start, end);
      while (!it.atEnd()) {
        addFragments(run, this, chars, it.getStartOffset() - start, it.getEndOffset() - start,
                     it.getMergedAttributes().getFontType(), fontPreferences, view.getFontRenderContext(), view.getTabFragment());
        it.advance();
      }
      view.getSizeManager().textLayoutPerformed(start, end);
      assert !fragments.isEmpty();
    }
    
    private Chunk subChunk(EditorView view, BidiRun run, int line, int targetStartOffset, int targetEndOffset,
                           @Nullable Runnable quickEvaluationListener) {
      assert targetStartOffset < endOffset;
      assert targetEndOffset > startOffset;
      int start = Math.max(startOffset, targetStartOffset);
      int end = Math.min(endOffset, targetEndOffset);
      if (quickEvaluationListener != null && fragments.isEmpty()) {
        quickEvaluationListener.run();
        return new ApproximationChunk(view, line, start, end);
      }
      if (start == startOffset && end == this.endOffset) {
        return this;
      }
      ensureLayout(view, run, line);
      Chunk chunk = new Chunk(start, end);
      int offset = startOffset;
      for (LineFragment fragment : fragments) {
        if (end <= offset) break;
        int endOffset = offset + fragment.getLength();
        if (start < endOffset) {
          chunk.fragments.add(fragment.subFragment(Math.max(start, offset) - offset, Math.min(end, endOffset) - offset));
        }
        offset = endOffset;
      }
      return chunk;
    }
    
    boolean isReal() {
      return true;
    }

    void clearCache() {
      fragments.clear();
    }
  }
  
  private static class ApproximationChunk extends Chunk {
    private ApproximationChunk(@NotNull EditorView view, int line, int start, int end) {
      super(start, end);
      int startColumn = view.getLogicalPositionCache().offsetToLogicalColumn(line, start);
      int endColumn = view.getLogicalPositionCache().offsetToLogicalColumn(line, end);
      fragments.add(new ApproximationFragment(end - start, endColumn - startColumn, view.getMaxCharWidth()));
    }

    @Override
    boolean isReal() {
      return false;
    }
  }

  private static class VisualOrderIterator implements Iterator<VisualFragment> {
    private final EditorView myView;
    private final int myLine;
    private final BidiRun[] myRuns;
    private int myRunIndex = 0;
    private int myChunkIndex = 0;
    private int myFragmentIndex = 0;
    private int myOffsetInsideRun = 0;
    private VisualFragment myFragment = new VisualFragment();

    private VisualOrderIterator(EditorView view, int line, 
                                float startX, int startVisualColumn, int startLogicalColumn, int startOffset, BidiRun[] runsInVisualOrder) {
      myView = view;
      myLine = line;
      myRuns = runsInVisualOrder;
      myFragment.startX = startX;
      myFragment.startVisualColumn = startVisualColumn;
      myFragment.startLogicalColumn = startLogicalColumn;
      myFragment.startOffset = startOffset;
    }

    @Override
    public boolean hasNext() {
      if (myRunIndex >= myRuns.length) return false;
      BidiRun run = myRuns[myRunIndex];
      Chunk[] chunks = run.getChunks();
      if (myChunkIndex >= chunks.length) return false;
      Chunk chunk = chunks[run.isRtl() ? chunks.length - 1 - myChunkIndex : myChunkIndex];
      if (myView != null) {
        chunk.ensureLayout(myView, run, myLine);
      }
      return myFragmentIndex < chunk.fragments.size();
    }

    @Override
    public VisualFragment next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      BidiRun run = myRuns[myRunIndex];

      if (myRunIndex == 0 && myChunkIndex == 0 && myFragmentIndex == 0) {
        myFragment.startLogicalColumn += (run.isRtl() ? run.endOffset : run.startOffset) - myFragment.startOffset; 
      }
      else {
        myFragment.startLogicalColumn = myFragment.getEndLogicalColumn();
        if (myChunkIndex == 0 && myFragmentIndex == 0) {
          myFragment.startLogicalColumn += (run.isRtl() ? run.endOffset : run.startOffset) - myFragment.getEndOffset();
        }
        myFragment.startVisualColumn = myFragment.getEndVisualColumn();
        myFragment.startX = myFragment.getEndX();
      }
      
      myFragment.isRtl = run.isRtl();
      Chunk[] chunks = run.getChunks();
      Chunk chunk = chunks[run.isRtl() ? chunks.length - 1 - myChunkIndex : myChunkIndex];
      assert !chunk.fragments.isEmpty();
      myFragment.delegate = chunk.fragments.get(run.isRtl() ? chunk.fragments.size() - 1 - myFragmentIndex : myFragmentIndex);
      myFragment.startOffset = run.isRtl() ? run.endOffset - myOffsetInsideRun : run.startOffset + myOffsetInsideRun;
      
      myOffsetInsideRun += myFragment.getLength();
      myFragmentIndex++;
      if (myFragmentIndex >= chunk.fragments.size()) {
        myFragmentIndex = 0;
        myChunkIndex++;
          if (myChunkIndex >= chunks.length) {
            myChunkIndex = 0;
            myOffsetInsideRun = 0;
            myRunIndex++;
          }
      }
      
      return myFragment;
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }
  
  static class VisualFragment {
    private LineFragment delegate;
    private int startOffset;
    private int startLogicalColumn;
    private int startVisualColumn;
    private float startX;
    private boolean isRtl;

    boolean isRtl() {
      return isRtl;
    }

    int getMinOffset() {
      return isRtl ? startOffset - getLength() : startOffset;
    }

    int getMaxOffset() {
      return isRtl ? startOffset : startOffset + getLength();
    }

    int getStartOffset() {
      return startOffset;
    }

    int getEndOffset() {
      return isRtl ? startOffset - getLength() : startOffset + getLength();
    }

    int getLength() {
      return delegate.getLength();
    }

    int getStartLogicalColumn() {
      return startLogicalColumn;
    }

    int getEndLogicalColumn() {
      return isRtl ? startLogicalColumn - getLogicalColumnCount() : startLogicalColumn + getLogicalColumnCount();
    }

    int getMinLogicalColumn() {
      return isRtl ? startLogicalColumn - getLogicalColumnCount() : startLogicalColumn;
    }

    int getMaxLogicalColumn() {
      return isRtl ? startLogicalColumn : startLogicalColumn + getLogicalColumnCount();
    }

    int getStartVisualColumn() {
      return startVisualColumn;
    }

    int getEndVisualColumn() {
      return startVisualColumn + getVisualColumnCount();
    }

    int getLogicalColumnCount() {
      return isRtl ? getLength() : delegate.getLogicalColumnCount(getMinLogicalColumn());
    }

    int getVisualColumnCount() {
      return delegate.getVisualColumnCount(startX);
    }

    float getStartX() {
      return startX;
    }

    float getEndX() {
      return delegate.offsetToX(startX, 0, getLength());
    }

    float getWidth() {
      return getEndX() - getStartX();
    }
    
    // column is expected to be between minLogicalColumn and maxLogicalColumn for this fragment
    int logicalToVisualColumn(int column) {
      return startVisualColumn + delegate.logicalToVisualColumn(startX, getMinLogicalColumn(), 
                                                                isRtl ? startLogicalColumn - column : column - startLogicalColumn);
    }

    // column is expected to be between startVisualColumn and endVisualColumn for this fragment
    int visualToLogicalColumn(int column) {
      int relativeLogicalColumn = delegate.visualToLogicalColumn(startX, getMinLogicalColumn(), column - startVisualColumn);
      return isRtl ? startLogicalColumn - relativeLogicalColumn : startLogicalColumn + relativeLogicalColumn;
    }

    // offset is expected to be between minOffset and maxOffset for this fragment
    float offsetToX(int offset) {
      return delegate.offsetToX(startX, 0, getRelativeOffset(offset));
    }

    // both startOffset and offset are expected to be between minOffset and maxOffset for this fragment
    float offsetToX(float startX, int startOffset, int offset) {
      return delegate.offsetToX(startX, getRelativeOffset(startOffset), getRelativeOffset(offset));
    }

    // x is expected to be between startX and endX for this fragment
    // returns array of two elements 
    // - first one is visual column, 
    // - second one is 1 if target location is closer to larger columns and 0 otherwise
    int[] xToVisualColumn(float x) {
      int[] column = delegate.xToVisualColumn(startX, x);
      column[0] += startVisualColumn;
      return column;
    }

    // column is expected to be between startVisualColumn and endVisualColumn for this fragment
    float visualColumnToX(int column) {
      return delegate.visualColumnToX(startX, column - startVisualColumn);
    }

    void draw(Graphics2D g, float x, float y) {
      delegate.draw(g, x, y, 0, getLength());
    }

    // columns are visual (relative to fragment's start)
    void draw(Graphics2D g, float x, float y, int startRelativeColumn, int endRelativeColumn) {
      delegate.draw(g, x, y, startRelativeColumn, endRelativeColumn);
    }

    private int getRelativeOffset(int offset) {
      return isRtl ? startOffset - offset : offset - startOffset;
    }
  }
}
