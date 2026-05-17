package com.github.stormino.config;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.theme.Theme;
import com.vaadin.flow.theme.aura.Aura;

@Push
@StyleSheet(Aura.STYLESHEET)
@StyleSheet("https://fonts.googleapis.com/css2?family=Roboto:wght@300;400;500;700&display=swap")
@Theme("vixsrc")
public class AppShell implements AppShellConfigurator {
}
