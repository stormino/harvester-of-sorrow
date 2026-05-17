package com.github.stormino.view;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import org.springframework.beans.factory.annotation.Value;

@SpringComponent
@UIScope
public class MainLayout extends AppLayout implements AfterNavigationObserver {

    private final String appVersion;

    public MainLayout(@Value("${app.version:unknown}") String appVersion) {
        this.appVersion = appVersion;
        createHeader();
        createDrawer();
        createBottomNav();
    }

    private void createHeader() {
        H1 appName = new H1("VixSrc Downloader");
        appName.getStyle()
                .set("font-size", "var(--aura-font-size-l, 1.125rem)")
                .set("margin", "0");

        Span version = new Span("v" + appVersion);
        version.getStyle()
                .set("font-size", "var(--aura-font-size-s, 0.875rem)")
                .set("color", "var(--vaadin-text-color-secondary)");

        HorizontalLayout header = new HorizontalLayout(
                new DrawerToggle(),
                appName,
                version
        );

        header.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        header.expand(appName);
        header.setWidthFull();
        header.getStyle().set("padding", "0 var(--vaadin-gap-m, 1rem)");

        addToNavbar(header);
    }

    private void createDrawer() {
        SideNav nav = new SideNav();

        nav.addItem(new SideNavItem("Search", SearchView.class, VaadinIcon.SEARCH.create()));
        nav.addItem(new SideNavItem("Downloads", DownloadQueueView.class, VaadinIcon.DOWNLOAD.create()));
        nav.addItem(new SideNavItem("Settings", SettingsView.class, VaadinIcon.COG.create()));

        addToDrawer(nav);
    }

    private void createBottomNav() {
        Div bottomNav = new Div();
        bottomNav.addClassName("mobile-bottom-nav");

        bottomNav.add(
                createBottomNavLink("Search", VaadinIcon.SEARCH, SearchView.class),
                createBottomNavLink("Downloads", VaadinIcon.DOWNLOAD, DownloadQueueView.class),
                createBottomNavLink("Settings", VaadinIcon.COG, SettingsView.class)
        );

        addToNavbar(bottomNav);
    }

    private RouterLink createBottomNavLink(String label, VaadinIcon icon, Class<? extends Component> viewClass) {
        RouterLink link = new RouterLink(viewClass);
        link.addClassName("mobile-bottom-nav-item");
        link.add(icon.create(), new Span(label));

        link.setHighlightAction((anchor, shouldHighlight) -> {
            if (shouldHighlight) {
                anchor.addClassName("active");
            } else {
                anchor.removeClassName("active");
            }
        });

        return link;
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        // Close drawer after navigation so it doesn't cover content on mobile
        setDrawerOpened(false);
    }
}
