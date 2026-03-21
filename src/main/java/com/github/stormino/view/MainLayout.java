package com.github.stormino.view;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import com.vaadin.flow.theme.lumo.LumoUtility;
import org.springframework.beans.factory.annotation.Value;

/**
 * Main application layout with sidebar navigation
 */
@SpringComponent
@UIScope
public class MainLayout extends AppLayout {

    private final String appVersion;

    public MainLayout(@Value("${app.version:unknown}") String appVersion) {
        this.appVersion = appVersion;
        createHeader();
        createDrawer();
    }

    private void createHeader() {
        H1 appName = new H1("VixSrc Downloader");
        appName.addClassNames(
                LumoUtility.FontSize.LARGE,
                LumoUtility.Margin.NONE
        );

        Span version = new Span("v" + appVersion);
        version.addClassNames(
                LumoUtility.FontSize.SMALL,
                LumoUtility.TextColor.SECONDARY
        );
        
        HorizontalLayout header = new HorizontalLayout(
                new DrawerToggle(),
                appName,
                version
        );
        
        header.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        header.expand(appName);
        header.setWidthFull();
        header.addClassNames(
                LumoUtility.Padding.Vertical.NONE,
                LumoUtility.Padding.Horizontal.MEDIUM
        );
        
        addToNavbar(header);
    }
    
    private void createDrawer() {
        SideNav nav = new SideNav();
        
        nav.addItem(new SideNavItem("Search", SearchView.class, VaadinIcon.SEARCH.create()));
        nav.addItem(new SideNavItem("Downloads", DownloadQueueView.class, VaadinIcon.DOWNLOAD.create()));
        nav.addItem(new SideNavItem("Settings", SettingsView.class, VaadinIcon.COG.create()));
        
        addToDrawer(nav);
    }
}
