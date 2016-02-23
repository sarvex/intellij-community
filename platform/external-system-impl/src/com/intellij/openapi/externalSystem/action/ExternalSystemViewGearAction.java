/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.intellij.openapi.externalSystem.action;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.externalSystem.view.ExternalProjectsViewImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Vladislav.Soroka
 * @since 10/31/2014
 */
public abstract class ExternalSystemViewGearAction extends ExternalSystemToggleAction {

  private ExternalProjectsViewImpl myView;

  @Override
  protected boolean isEnabled(AnActionEvent e) {
    if (!super.isEnabled(e)) return false;
    return getView() != null;
  }

  @Override
  protected boolean doIsSelected(AnActionEvent e) {
    final ExternalProjectsViewImpl view = getView();
    return view != null && isSelected(view);
  }

  @Override
  public void setSelected(AnActionEvent e, boolean state) {
    final ExternalProjectsViewImpl view = getView();
    if (view != null){
      setSelected(view, state);
    }
  }

  protected abstract boolean isSelected(@NotNull ExternalProjectsViewImpl view);

  protected abstract void setSelected(@NotNull ExternalProjectsViewImpl view, boolean value);

  @Nullable
  protected ExternalProjectsViewImpl getView() {
    return myView;
  }

  public void setView(ExternalProjectsViewImpl view) {
    myView = view;
  }
}
