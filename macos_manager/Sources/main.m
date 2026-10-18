#import <Cocoa/Cocoa.h>
#import <sys/types.h>
#import <unistd.h>

static NSString * const kHealthURLKey = @"healthURL";
static NSString * const kRefreshIntervalKey = @"refreshInterval";

@interface StatusCardView : NSView
@property NSTextField *titleLabel;
@property NSTextField *valueLabel;
@property NSTextField *detailLabel;
@property NSView *dot;
- (instancetype)initWithTitle:(NSString *)title;
- (void)updateValue:(NSString *)value detail:(NSString *)detail healthy:(BOOL)healthy;
@end

@implementation StatusCardView
- (instancetype)initWithTitle:(NSString *)title {
    self = [super initWithFrame:NSZeroRect];
    if (!self) return nil;
    self.wantsLayer = YES;
    self.layer.cornerRadius = 14;
    self.layer.backgroundColor = NSColor.controlBackgroundColor.CGColor;
    self.layer.borderColor = [NSColor.separatorColor colorWithAlphaComponent:0.45].CGColor;
    self.layer.borderWidth = 1;

    _dot = [[NSView alloc] initWithFrame:NSZeroRect];
    _dot.wantsLayer = YES;
    _dot.layer.cornerRadius = 4;
    _dot.translatesAutoresizingMaskIntoConstraints = NO;

    _titleLabel = [NSTextField labelWithString:title];
    _titleLabel.font = [NSFont systemFontOfSize:12 weight:NSFontWeightMedium];
    _titleLabel.textColor = NSColor.secondaryLabelColor;
    _titleLabel.translatesAutoresizingMaskIntoConstraints = NO;

    _valueLabel = [NSTextField labelWithString:@"检查中"];
    _valueLabel.font = [NSFont systemFontOfSize:17 weight:NSFontWeightSemibold];
    _valueLabel.translatesAutoresizingMaskIntoConstraints = NO;

    _detailLabel = [NSTextField labelWithString:@""];
    _detailLabel.font = [NSFont systemFontOfSize:11];
    _detailLabel.textColor = NSColor.tertiaryLabelColor;
    _detailLabel.lineBreakMode = NSLineBreakByTruncatingTail;
    _detailLabel.translatesAutoresizingMaskIntoConstraints = NO;

    [self addSubview:_dot];
    [self addSubview:_titleLabel];
    [self addSubview:_valueLabel];
    [self addSubview:_detailLabel];
    [NSLayoutConstraint activateConstraints:@[
        [_dot.topAnchor constraintEqualToAnchor:self.topAnchor constant:15],
        [_dot.trailingAnchor constraintEqualToAnchor:self.trailingAnchor constant:-15],
        [_dot.widthAnchor constraintEqualToConstant:8],
        [_dot.heightAnchor constraintEqualToConstant:8],
        [_titleLabel.topAnchor constraintEqualToAnchor:self.topAnchor constant:14],
        [_titleLabel.leadingAnchor constraintEqualToAnchor:self.leadingAnchor constant:15],
        [_titleLabel.trailingAnchor constraintLessThanOrEqualToAnchor:_dot.leadingAnchor constant:-8],
        [_valueLabel.topAnchor constraintEqualToAnchor:_titleLabel.bottomAnchor constant:12],
        [_valueLabel.leadingAnchor constraintEqualToAnchor:self.leadingAnchor constant:15],
        [_valueLabel.trailingAnchor constraintEqualToAnchor:self.trailingAnchor constant:-15],
        [_detailLabel.topAnchor constraintEqualToAnchor:_valueLabel.bottomAnchor constant:5],
        [_detailLabel.leadingAnchor constraintEqualToAnchor:self.leadingAnchor constant:15],
        [_detailLabel.trailingAnchor constraintEqualToAnchor:self.trailingAnchor constant:-15],
        [_detailLabel.bottomAnchor constraintLessThanOrEqualToAnchor:self.bottomAnchor constant:-14]
    ]];
    return self;
}

- (void)updateValue:(NSString *)value detail:(NSString *)detail healthy:(BOOL)healthy {
    self.valueLabel.stringValue = value ?: @"—";
    self.detailLabel.stringValue = detail ?: @"";
    self.dot.layer.backgroundColor = (healthy ? NSColor.systemGreenColor : NSColor.systemOrangeColor).CGColor;
}
@end

@interface ServiceRowView : NSView
@property NSString *serviceID;
@property NSTextField *statusLabel;
@property NSTextField *pidLabel;
@property NSPopUpButton *actionMenu;
@end

@implementation ServiceRowView
@end

@interface FlippedView : NSView
@end

@implementation FlippedView
- (BOOL)isFlipped { return YES; }
@end

@interface AppDelegate : NSObject <NSApplicationDelegate>
@property NSWindow *window;
@property NSStatusItem *statusItem;
@property NSTextField *summaryLabel;
@property NSTextField *refreshLabel;
@property StatusCardView *overallCard;
@property StatusCardView *codexCard;
@property StatusCardView *phoneCard;
@property StatusCardView *latencyCard;
@property NSMutableDictionary<NSString *, ServiceRowView *> *serviceRows;
@property NSArray<NSDictionary *> *services;
@property NSTimer *timer;
@property BOOL refreshing;
@property BOOL working;
@property NSDictionary *latestHealth;
@property NSInteger latestLatency;
@property NSMutableDictionary<NSString *, NSDictionary *> *latestSnapshots;
@end

@implementation AppDelegate

- (void)applicationDidFinishLaunching:(NSNotification *)notification {
    [NSUserDefaults.standardUserDefaults registerDefaults:@{
        kHealthURLKey: @"http://127.0.0.1:18880/health",
        kRefreshIntervalKey: @5.0
    }];
    self.services = @[
        @{ @"id": @"relay", @"title": @"本地中继", @"subtitle": @"接收手机连接并转发消息", @"symbol": @"arrow.triangle.branch", @"label": @"org.codexmobile.bridge.relay", @"optional": @NO },
        @{ @"id": @"bridge", @"title": @"Mac 桥接", @"subtitle": @"连接 Codex 后台并同步会话", @"symbol": @"macbook.and.iphone", @"label": @"org.codexmobile.bridge.mac", @"optional": @NO },
        @{ @"id": @"tunnel", @"title": @"公网隧道（可选）", @"subtitle": @"FRP、Cloudflare Tunnel 或其他转发服务", @"symbol": @"network", @"label": @"org.codexmobile.bridge.tunnel", @"optional": @YES }
    ];
    self.serviceRows = [NSMutableDictionary dictionary];
    self.latestSnapshots = [NSMutableDictionary dictionary];
    self.latestLatency = -1;
    [self buildWindow];
    [self buildMenuBar];
    [self scheduleTimer];
    [self refresh:nil];
}

- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)sender { return NO; }

- (void)buildWindow {
    NSRect rect = NSMakeRect(0, 0, 820, 690);
    self.window = [[NSWindow alloc] initWithContentRect:rect
                                             styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable | NSWindowStyleMaskMiniaturizable | NSWindowStyleMaskResizable
                                               backing:NSBackingStoreBuffered
                                                 defer:NO];
    self.window.title = @"Codex 手机桥接";
    self.window.minSize = NSMakeSize(720, 620);
    self.window.titlebarAppearsTransparent = YES;
    self.window.backgroundColor = NSColor.windowBackgroundColor;
    [self.window center];

    NSView *root = self.window.contentView;
    NSVisualEffectView *header = [[NSVisualEffectView alloc] initWithFrame:NSZeroRect];
    header.material = NSVisualEffectMaterialHeaderView;
    header.blendingMode = NSVisualEffectBlendingModeWithinWindow;
    header.translatesAutoresizingMaskIntoConstraints = NO;
    header.hidden = YES;
    [root addSubview:header];

    NSView *logo = [[NSView alloc] initWithFrame:NSZeroRect];
    logo.wantsLayer = YES;
    logo.layer.cornerRadius = 12;
    logo.layer.backgroundColor = NSColor.labelColor.CGColor;
    logo.translatesAutoresizingMaskIntoConstraints = NO;
    [header addSubview:logo];
    NSImageView *logoImage = [[NSImageView alloc] initWithFrame:NSZeroRect];
    logoImage.image = [NSImage imageWithSystemSymbolName:@"waveform.path.ecg" accessibilityDescription:nil];
    logoImage.contentTintColor = NSColor.windowBackgroundColor;
    logoImage.symbolConfiguration = [NSImageSymbolConfiguration configurationWithPointSize:22 weight:NSFontWeightSemibold];
    logoImage.translatesAutoresizingMaskIntoConstraints = NO;
    [logo addSubview:logoImage];

    NSTextField *title = [NSTextField labelWithString:@"Codex 手机桥接"];
    title.font = [NSFont systemFontOfSize:22 weight:NSFontWeightSemibold];
    title.translatesAutoresizingMaskIntoConstraints = NO;
    [header addSubview:title];
    self.summaryLabel = [NSTextField labelWithString:@"正在检查后台服务"];
    self.summaryLabel.font = [NSFont systemFontOfSize:13];
    self.summaryLabel.textColor = NSColor.secondaryLabelColor;
    self.summaryLabel.translatesAutoresizingMaskIntoConstraints = NO;
    [header addSubview:self.summaryLabel];

    NSButton *refreshButton = [NSButton buttonWithImage:[NSImage imageWithSystemSymbolName:@"arrow.clockwise" accessibilityDescription:nil]
                                                 target:self action:@selector(refresh:)];
    refreshButton.bezelStyle = NSBezelStyleTexturedRounded;
    refreshButton.toolTip = @"立即刷新";
    refreshButton.translatesAutoresizingMaskIntoConstraints = NO;
    [header addSubview:refreshButton];
    NSButton *settingsButton = [NSButton buttonWithImage:[NSImage imageWithSystemSymbolName:@"gearshape" accessibilityDescription:nil]
                                                  target:self action:@selector(showSettings:)];
    settingsButton.bezelStyle = NSBezelStyleTexturedRounded;
    settingsButton.toolTip = @"设置";
    settingsButton.translatesAutoresizingMaskIntoConstraints = NO;
    [header addSubview:settingsButton];

    NSScrollView *scroll = [[NSScrollView alloc] initWithFrame:NSZeroRect];
    scroll.hasVerticalScroller = YES;
    scroll.drawsBackground = NO;
    scroll.translatesAutoresizingMaskIntoConstraints = NO;
    [root addSubview:scroll];
    NSView *content = [[FlippedView alloc] initWithFrame:NSZeroRect];
    content.translatesAutoresizingMaskIntoConstraints = NO;
    scroll.documentView = content;

    NSTextField *overviewTitle = [self sectionTitle:@"连接概况"];
    NSTextField *overviewHint = [self hintLabel:@"显示真实连接，不只是进程是否存在"];
    [content addSubview:overviewTitle];
    [content addSubview:overviewHint];

    self.overallCard = [[StatusCardView alloc] initWithTitle:@"整体状态"];
    self.codexCard = [[StatusCardView alloc] initWithTitle:@"Codex"];
    self.phoneCard = [[StatusCardView alloc] initWithTitle:@"手机"];
    self.latencyCard = [[StatusCardView alloc] initWithTitle:@"本地延迟"];
    NSArray *cards = @[self.overallCard, self.codexCard, self.phoneCard, self.latencyCard];
    NSStackView *cardStack = [NSStackView stackViewWithViews:cards];
    cardStack.orientation = NSUserInterfaceLayoutOrientationHorizontal;
    cardStack.spacing = 12;
    cardStack.distribution = NSStackViewDistributionFillEqually;
    cardStack.translatesAutoresizingMaskIntoConstraints = NO;
    [content addSubview:cardStack];

    NSTextField *serviceTitle = [self sectionTitle:@"后台服务"];
    NSTextField *serviceHint = [self hintLabel:@"可以单独启动、停止或重启"];
    [content addSubview:serviceTitle];
    [content addSubview:serviceHint];

    NSStackView *serviceStack = [[NSStackView alloc] initWithFrame:NSZeroRect];
    serviceStack.orientation = NSUserInterfaceLayoutOrientationVertical;
    serviceStack.spacing = 1;
    serviceStack.wantsLayer = YES;
    serviceStack.layer.cornerRadius = 14;
    serviceStack.layer.backgroundColor = NSColor.controlBackgroundColor.CGColor;
    serviceStack.translatesAutoresizingMaskIntoConstraints = NO;
    [content addSubview:serviceStack];
    for (NSDictionary *service in self.services) {
        ServiceRowView *row = [self makeServiceRow:service];
        [serviceStack addArrangedSubview:row];
        [row.heightAnchor constraintEqualToConstant:76].active = YES;
        self.serviceRows[service[@"id"]] = row;
    }

    NSButton *startAll = [NSButton buttonWithTitle:@"启动全部" target:self action:@selector(startAll:)];
    startAll.bezelStyle = NSBezelStyleRounded;
    startAll.keyEquivalent = @"\r";
    NSButton *restartAll = [NSButton buttonWithTitle:@"重启全部" target:self action:@selector(restartAll:)];
    restartAll.bezelStyle = NSBezelStyleRounded;
    NSButton *stopAll = [NSButton buttonWithTitle:@"停止全部" target:self action:@selector(stopAll:)];
    stopAll.bezelStyle = NSBezelStyleRounded;
    NSButton *config = [NSButton buttonWithTitle:@"配置目录" target:self action:@selector(openConfiguration:)];
    config.bezelStyle = NSBezelStyleRounded;
    NSButton *logs = [NSButton buttonWithTitle:@"运行日志" target:self action:@selector(openLogs:)];
    logs.bezelStyle = NSBezelStyleRounded;
    NSButton *refreshNow = [NSButton buttonWithTitle:@"立即刷新" target:self action:@selector(refresh:)];
    refreshNow.bezelStyle = NSBezelStyleRounded;
    NSButton *settingsNow = [NSButton buttonWithTitle:@"设置" target:self action:@selector(showSettings:)];
    settingsNow.bezelStyle = NSBezelStyleRounded;
    NSStackView *buttons = [NSStackView stackViewWithViews:@[startAll, restartAll, stopAll, refreshNow, settingsNow, config, logs]];
    buttons.orientation = NSUserInterfaceLayoutOrientationHorizontal;
    buttons.spacing = 10;
    buttons.translatesAutoresizingMaskIntoConstraints = NO;
    [content addSubview:buttons];

    self.refreshLabel = [self hintLabel:@""];
    self.refreshLabel.alignment = NSTextAlignmentRight;
    [content addSubview:self.refreshLabel];

    [NSLayoutConstraint activateConstraints:@[
        [header.topAnchor constraintEqualToAnchor:root.topAnchor],
        [header.leadingAnchor constraintEqualToAnchor:root.leadingAnchor],
        [header.trailingAnchor constraintEqualToAnchor:root.trailingAnchor],
        [header.heightAnchor constraintEqualToConstant:0],
        [logo.leadingAnchor constraintEqualToAnchor:header.leadingAnchor constant:24],
        [logo.centerYAnchor constraintEqualToAnchor:header.centerYAnchor],
        [logo.widthAnchor constraintEqualToConstant:48],
        [logo.heightAnchor constraintEqualToConstant:48],
        [logoImage.centerXAnchor constraintEqualToAnchor:logo.centerXAnchor],
        [logoImage.centerYAnchor constraintEqualToAnchor:logo.centerYAnchor],
        [logoImage.widthAnchor constraintEqualToConstant:27],
        [logoImage.heightAnchor constraintEqualToConstant:27],
        [title.leadingAnchor constraintEqualToAnchor:logo.trailingAnchor constant:14],
        [title.topAnchor constraintEqualToAnchor:logo.topAnchor constant:2],
        [self.summaryLabel.leadingAnchor constraintEqualToAnchor:title.leadingAnchor],
        [self.summaryLabel.topAnchor constraintEqualToAnchor:title.bottomAnchor constant:5],
        [settingsButton.trailingAnchor constraintEqualToAnchor:header.trailingAnchor constant:-24],
        [settingsButton.centerYAnchor constraintEqualToAnchor:header.centerYAnchor],
        [refreshButton.trailingAnchor constraintEqualToAnchor:settingsButton.leadingAnchor constant:-10],
        [refreshButton.centerYAnchor constraintEqualToAnchor:header.centerYAnchor],
        [scroll.topAnchor constraintEqualToAnchor:header.bottomAnchor],
        [scroll.leadingAnchor constraintEqualToAnchor:root.leadingAnchor],
        [scroll.trailingAnchor constraintEqualToAnchor:root.trailingAnchor],
        [scroll.bottomAnchor constraintEqualToAnchor:root.bottomAnchor],
        [content.widthAnchor constraintEqualToAnchor:scroll.contentView.widthAnchor],
        [overviewTitle.topAnchor constraintEqualToAnchor:content.topAnchor constant:24],
        [overviewTitle.leadingAnchor constraintEqualToAnchor:content.leadingAnchor constant:24],
        [overviewHint.leadingAnchor constraintEqualToAnchor:overviewTitle.trailingAnchor constant:10],
        [overviewHint.centerYAnchor constraintEqualToAnchor:overviewTitle.centerYAnchor],
        [cardStack.topAnchor constraintEqualToAnchor:overviewTitle.bottomAnchor constant:13],
        [cardStack.leadingAnchor constraintEqualToAnchor:content.leadingAnchor constant:24],
        [cardStack.trailingAnchor constraintEqualToAnchor:content.trailingAnchor constant:-24],
        [cardStack.heightAnchor constraintEqualToConstant:122],
        [serviceTitle.topAnchor constraintEqualToAnchor:cardStack.bottomAnchor constant:24],
        [serviceTitle.leadingAnchor constraintEqualToAnchor:content.leadingAnchor constant:24],
        [serviceHint.leadingAnchor constraintEqualToAnchor:serviceTitle.trailingAnchor constant:10],
        [serviceHint.centerYAnchor constraintEqualToAnchor:serviceTitle.centerYAnchor],
        [serviceStack.topAnchor constraintEqualToAnchor:serviceTitle.bottomAnchor constant:13],
        [serviceStack.leadingAnchor constraintEqualToAnchor:content.leadingAnchor constant:24],
        [serviceStack.trailingAnchor constraintEqualToAnchor:content.trailingAnchor constant:-24],
        [buttons.topAnchor constraintEqualToAnchor:serviceStack.bottomAnchor constant:20],
        [buttons.leadingAnchor constraintEqualToAnchor:content.leadingAnchor constant:24],
        [self.refreshLabel.centerYAnchor constraintEqualToAnchor:buttons.centerYAnchor],
        [self.refreshLabel.trailingAnchor constraintEqualToAnchor:content.trailingAnchor constant:-24],
        [buttons.trailingAnchor constraintLessThanOrEqualToAnchor:self.refreshLabel.leadingAnchor constant:-12],
        [buttons.bottomAnchor constraintEqualToAnchor:content.bottomAnchor constant:-26]
    ]];
    [self.window makeKeyAndOrderFront:nil];
    [NSApp activateIgnoringOtherApps:YES];
}

- (NSTextField *)sectionTitle:(NSString *)text {
    NSTextField *field = [NSTextField labelWithString:text];
    field.font = [NSFont systemFontOfSize:15 weight:NSFontWeightSemibold];
    field.translatesAutoresizingMaskIntoConstraints = NO;
    return field;
}

- (NSTextField *)hintLabel:(NSString *)text {
    NSTextField *field = [NSTextField labelWithString:text];
    field.font = [NSFont systemFontOfSize:11];
    field.textColor = NSColor.secondaryLabelColor;
    field.translatesAutoresizingMaskIntoConstraints = NO;
    return field;
}

- (ServiceRowView *)makeServiceRow:(NSDictionary *)service {
    ServiceRowView *row = [[ServiceRowView alloc] initWithFrame:NSZeroRect];
    row.serviceID = service[@"id"];
    row.translatesAutoresizingMaskIntoConstraints = NO;

    NSImageView *icon = [[NSImageView alloc] initWithFrame:NSZeroRect];
    icon.image = [NSImage imageWithSystemSymbolName:service[@"symbol"] accessibilityDescription:nil];
    icon.symbolConfiguration = [NSImageSymbolConfiguration configurationWithPointSize:18 weight:NSFontWeightMedium];
    icon.translatesAutoresizingMaskIntoConstraints = NO;
    [row addSubview:icon];

    NSTextField *title = [NSTextField labelWithString:service[@"title"]];
    title.font = [NSFont systemFontOfSize:14 weight:NSFontWeightMedium];
    title.translatesAutoresizingMaskIntoConstraints = NO;
    [row addSubview:title];
    NSTextField *subtitle = [NSTextField labelWithString:service[@"subtitle"]];
    subtitle.font = [NSFont systemFontOfSize:11];
    subtitle.textColor = NSColor.secondaryLabelColor;
    subtitle.translatesAutoresizingMaskIntoConstraints = NO;
    [row addSubview:subtitle];

    row.pidLabel = [NSTextField labelWithString:@""];
    row.pidLabel.font = [NSFont monospacedDigitSystemFontOfSize:10 weight:NSFontWeightRegular];
    row.pidLabel.textColor = NSColor.tertiaryLabelColor;
    row.pidLabel.translatesAutoresizingMaskIntoConstraints = NO;
    [row addSubview:row.pidLabel];

    row.statusLabel = [NSTextField labelWithString:@"● 检查中"];
    row.statusLabel.font = [NSFont systemFontOfSize:12 weight:NSFontWeightMedium];
    row.statusLabel.translatesAutoresizingMaskIntoConstraints = NO;
    [row addSubview:row.statusLabel];

    row.actionMenu = [[NSPopUpButton alloc] initWithFrame:NSZeroRect pullsDown:YES];
    [row.actionMenu addItemWithTitle:@"•••"];
    [row.actionMenu addItemWithTitle:@"启动"];
    [row.actionMenu addItemWithTitle:@"重启"];
    [row.actionMenu addItemWithTitle:@"停止"];
    row.actionMenu.target = self;
    row.actionMenu.action = @selector(serviceActionSelected:);
    row.actionMenu.translatesAutoresizingMaskIntoConstraints = NO;
    [row addSubview:row.actionMenu];

    [NSLayoutConstraint activateConstraints:@[
        [icon.leadingAnchor constraintEqualToAnchor:row.leadingAnchor constant:18],
        [icon.centerYAnchor constraintEqualToAnchor:row.centerYAnchor],
        [icon.widthAnchor constraintEqualToConstant:28],
        [icon.heightAnchor constraintEqualToConstant:28],
        [title.leadingAnchor constraintEqualToAnchor:icon.trailingAnchor constant:13],
        [title.topAnchor constraintEqualToAnchor:row.topAnchor constant:18],
        [subtitle.leadingAnchor constraintEqualToAnchor:title.leadingAnchor],
        [subtitle.topAnchor constraintEqualToAnchor:title.bottomAnchor constant:4],
        [row.actionMenu.trailingAnchor constraintEqualToAnchor:row.trailingAnchor constant:-14],
        [row.actionMenu.centerYAnchor constraintEqualToAnchor:row.centerYAnchor],
        [row.actionMenu.widthAnchor constraintEqualToConstant:48],
        [row.statusLabel.trailingAnchor constraintEqualToAnchor:row.actionMenu.leadingAnchor constant:-14],
        [row.statusLabel.centerYAnchor constraintEqualToAnchor:row.centerYAnchor],
        [row.pidLabel.trailingAnchor constraintEqualToAnchor:row.statusLabel.leadingAnchor constant:-16],
        [row.pidLabel.centerYAnchor constraintEqualToAnchor:row.centerYAnchor]
    ]];
    return row;
}

- (void)buildMenuBar {
    self.statusItem = [NSStatusBar.systemStatusBar statusItemWithLength:NSSquareStatusItemLength];
    self.statusItem.button.image = [NSImage imageWithSystemSymbolName:@"waveform.path.ecg" accessibilityDescription:@"Codex 手机桥接"];
    NSMenu *menu = [[NSMenu alloc] init];
    [menu addItemWithTitle:@"打开控制中心" action:@selector(showWindow:) keyEquivalent:@""];
    NSMenuItem *status = [menu addItemWithTitle:@"正在检查连接" action:nil keyEquivalent:@""];
    status.tag = 9001;
    [menu addItem:NSMenuItem.separatorItem];
    [menu addItemWithTitle:@"启动全部" action:@selector(startAll:) keyEquivalent:@""];
    [menu addItemWithTitle:@"重启全部" action:@selector(restartAll:) keyEquivalent:@""];
    [menu addItemWithTitle:@"停止全部" action:@selector(stopAll:) keyEquivalent:@""];
    [menu addItem:NSMenuItem.separatorItem];
    [menu addItemWithTitle:@"退出管理器" action:@selector(terminate:) keyEquivalent:@"q"];
    for (NSMenuItem *item in menu.itemArray) item.target = self;
    self.statusItem.menu = menu;
}

- (void)scheduleTimer {
    [self.timer invalidate];
    NSTimeInterval interval = MAX(2.0, [NSUserDefaults.standardUserDefaults doubleForKey:kRefreshIntervalKey]);
    self.timer = [NSTimer scheduledTimerWithTimeInterval:interval target:self selector:@selector(refresh:) userInfo:nil repeats:YES];
}

- (void)refresh:(id)sender {
    if (self.refreshing) return;
    self.refreshing = YES;
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        NSMutableDictionary *snapshots = [NSMutableDictionary dictionary];
        for (NSDictionary *service in self.services) snapshots[service[@"id"]] = [self inspectService:service];
        NSDictionary *healthResult = [self fetchHealth];
        dispatch_async(dispatch_get_main_queue(), ^{
            self.latestSnapshots = snapshots;
            self.latestHealth = healthResult[@"health"];
            self.latestLatency = [healthResult[@"latency"] integerValue];
            [self renderState];
            self.refreshing = NO;
        });
    });
}

- (NSDictionary *)inspectService:(NSDictionary *)service {
    NSString *label = service[@"label"];
    NSString *target = [NSString stringWithFormat:@"gui/%d/%@", getuid(), label];
    NSDictionary *result = [self run:@"/bin/launchctl" arguments:@[@"print", target]];
    NSString *plist = [NSHomeDirectory() stringByAppendingPathComponent:[NSString stringWithFormat:@"Library/LaunchAgents/%@.plist", label]];
    BOOL installed = [NSFileManager.defaultManager fileExistsAtPath:plist];
    if ([result[@"code"] intValue] != 0) return @{ @"state": installed ? @"stopped" : @"missing", @"pid": @0 };
    NSString *output = result[@"out"];
    NSString *state = [self capture:@"state\\s*=\\s*([^\\n]+)" text:output] ?: @"stopped";
    NSString *pidText = [self capture:@"pid\\s*=\\s*(\\d+)" text:output] ?: @"0";
    return @{ @"state": [state isEqualToString:@"running"] ? @"running" : @"stopped", @"pid": @([pidText integerValue]) };
}

- (NSDictionary *)fetchHealth {
    NSString *address = [NSUserDefaults.standardUserDefaults stringForKey:kHealthURLKey];
    NSURL *url = [NSURL URLWithString:address ?: @""];
    if (!url) return @{ @"latency": @(-1) };
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url cachePolicy:NSURLRequestReloadIgnoringLocalCacheData timeoutInterval:3];
    NSDate *start = NSDate.date;
    __block NSError *error = nil;
    __block NSData *data = nil;
    __block NSURLResponse *response = nil;
    dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
    NSURLSessionConfiguration *configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration;
    NSURLSession *session = [NSURLSession sessionWithConfiguration:configuration];
    NSURLSessionDataTask *task = [session dataTaskWithRequest:request completionHandler:^(NSData *received, NSURLResponse *receivedResponse, NSError *receivedError) {
        data = received;
        response = receivedResponse;
        error = receivedError;
        dispatch_semaphore_signal(semaphore);
    }];
    [task resume];
    dispatch_semaphore_wait(semaphore, dispatch_time(DISPATCH_TIME_NOW, 4 * NSEC_PER_SEC));
    [session invalidateAndCancel];
    NSInteger latency = (NSInteger)(-[start timeIntervalSinceNow] * 1000.0);
    if (!data || error) return @{ @"latency": @(-1) };
    NSDictionary *json = [NSJSONSerialization JSONObjectWithData:data options:0 error:&error];
    if (![json isKindOfClass:NSDictionary.class] || error) return @{ @"latency": @(-1) };
    return @{ @"health": json, @"latency": @(latency) };
}

- (void)renderState {
    BOOL servicesRunning = YES;
    for (NSDictionary *service in self.services) {
        NSDictionary *snapshot = self.latestSnapshots[service[@"id"]];
        NSString *state = snapshot[@"state"];
        ServiceRowView *row = self.serviceRows[service[@"id"]];
        BOOL running = [state isEqualToString:@"running"];
        if (![service[@"optional"] boolValue]) servicesRunning = servicesRunning && running;
        if (running) {
            row.statusLabel.stringValue = @"● 运行中";
            row.statusLabel.textColor = NSColor.systemGreenColor;
            NSInteger pid = [snapshot[@"pid"] integerValue];
            row.pidLabel.stringValue = pid > 0 ? [NSString stringWithFormat:@"PID %ld", (long)pid] : @"";
        } else if ([state isEqualToString:@"missing"]) {
            row.statusLabel.stringValue = @"● 未安装";
            row.statusLabel.textColor = NSColor.systemRedColor;
            row.pidLabel.stringValue = @"";
        } else {
            row.statusLabel.stringValue = @"● 已停止";
            row.statusLabel.textColor = NSColor.systemOrangeColor;
            row.pidLabel.stringValue = @"";
        }
        row.actionMenu.enabled = ![state isEqualToString:@"missing"];
    }
    BOOL relayOK = [self.latestHealth[@"ok"] boolValue];
    BOOL macConnected = [self.latestHealth[@"macConnected"] boolValue];
    NSInteger phones = [self.latestHealth[@"androidConnections"] integerValue];
    BOOL overall = servicesRunning && relayOK && macConnected;

    NSString *summary;
    if (overall && phones > 0) summary = @"手机、Mac 与 Codex 已连接";
    else if (overall) summary = @"服务正常，等待手机连接";
    else if (servicesRunning) summary = @"服务已启动，正在连接 Codex";
    else summary = @"有服务尚未正常运行";
    self.summaryLabel.stringValue = summary;
    [self.overallCard updateValue:overall ? @"正常" : @"需检查"
                             detail:servicesRunning ? @"两个核心服务已运行" : @"有核心服务未运行"
                            healthy:overall];
    [self.codexCard updateValue:macConnected ? @"已连接" : @"未连接" detail:@"后台 app-server" healthy:macConnected];
    [self.phoneCard updateValue:phones > 0 ? [NSString stringWithFormat:@"%ld 台在线", (long)phones] : @"未连接"
                           detail:phones > 0 ? @"WebSocket 在线" : @"等待手机连接" healthy:phones > 0];
    [self.latencyCard updateValue:self.latestLatency >= 0 ? [NSString stringWithFormat:@"%ld ms", (long)self.latestLatency] : @"—"
                             detail:@"Mac 内部健康检查" healthy:self.latestLatency >= 0];
    NSDateFormatter *formatter = [[NSDateFormatter alloc] init];
    formatter.timeStyle = NSDateFormatterMediumStyle;
    self.refreshLabel.stringValue = [NSString stringWithFormat:@"更新于 %@", [formatter stringFromDate:NSDate.date]];
    self.statusItem.button.image = [NSImage imageWithSystemSymbolName:overall ? @"waveform.path.ecg" : @"exclamationmark.heart" accessibilityDescription:nil];
    NSMenuItem *menuStatus = [self.statusItem.menu itemWithTag:9001];
    menuStatus.title = summary;
}

- (void)serviceActionSelected:(NSPopUpButton *)sender {
    ServiceRowView *row = (ServiceRowView *)sender.superview;
    NSInteger index = sender.indexOfSelectedItem;
    [sender selectItemAtIndex:0];
    if (index < 1 || index > 3) return;
    NSString *action = index == 1 ? @"start" : (index == 2 ? @"restart" : @"stop");
    [self performAction:action serviceID:row.serviceID completion:nil];
}

- (void)performAction:(NSString *)action serviceID:(NSString *)serviceID completion:(dispatch_block_t)completion {
    if (self.working && !completion) return;
    NSDictionary *service = [self serviceByID:serviceID];
    if (!service) return;
    self.working = YES;
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        [self executeAction:action service:service];
        dispatch_async(dispatch_get_main_queue(), ^{
            self.working = NO;
            if (completion) completion();
            [self refresh:nil];
        });
    });
}

- (NSDictionary *)executeAction:(NSString *)action service:(NSDictionary *)service {
    NSString *label = service[@"label"];
    NSString *domain = [NSString stringWithFormat:@"gui/%d", getuid()];
    NSString *target = [NSString stringWithFormat:@"%@/%@", domain, label];
    NSString *plist = [NSHomeDirectory() stringByAppendingPathComponent:[NSString stringWithFormat:@"Library/LaunchAgents/%@.plist", label]];
    BOOL loaded = [[self run:@"/bin/launchctl" arguments:@[@"print", target]][@"code"] intValue] == 0;
    if ([action isEqualToString:@"stop"]) {
        return loaded ? [self run:@"/bin/launchctl" arguments:@[@"bootout", target]] : @{ @"code": @0 };
    }
    if ([action isEqualToString:@"restart"] && loaded) {
        return [self run:@"/bin/launchctl" arguments:@[@"kickstart", @"-k", target]];
    }
    if (!loaded) {
        if (![NSFileManager.defaultManager fileExistsAtPath:plist]) return @{ @"code": @2, @"err": @"没有找到服务配置文件" };
        NSDictionary *boot = [self run:@"/bin/launchctl" arguments:@[@"bootstrap", domain, plist]];
        if ([boot[@"code"] intValue] != 0) return boot;
    }
    return [self run:@"/bin/launchctl" arguments:@[@"kickstart", @"-k", target]];
}

- (void)performAll:(NSString *)action {
    if (self.working) return;
    self.working = YES;
    NSArray *order = [action isEqualToString:@"stop"] ? [[self.services reverseObjectEnumerator] allObjects] : self.services;
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        for (NSDictionary *service in order) {
            NSString *plist = [NSHomeDirectory() stringByAppendingPathComponent:[NSString stringWithFormat:@"Library/LaunchAgents/%@.plist", service[@"label"]]];
            if ([service[@"optional"] boolValue] && ![NSFileManager.defaultManager fileExistsAtPath:plist]) continue;
            [self executeAction:action service:service];
        }
        [NSThread sleepForTimeInterval:0.45];
        dispatch_async(dispatch_get_main_queue(), ^{
            self.working = NO;
            [self refresh:nil];
        });
    });
}

- (NSDictionary *)serviceByID:(NSString *)serviceID {
    for (NSDictionary *service in self.services) if ([service[@"id"] isEqualToString:serviceID]) return service;
    return nil;
}

- (NSDictionary *)run:(NSString *)executable arguments:(NSArray<NSString *> *)arguments {
    NSTask *task = [[NSTask alloc] init];
    NSPipe *outPipe = [NSPipe pipe];
    NSPipe *errPipe = [NSPipe pipe];
    task.executableURL = [NSURL fileURLWithPath:executable];
    task.arguments = arguments;
    task.standardOutput = outPipe;
    task.standardError = errPipe;
    NSError *error = nil;
    if (![task launchAndReturnError:&error]) return @{ @"code": @(-1), @"out": @"", @"err": error.localizedDescription ?: @"启动命令失败" };
    [task waitUntilExit];
    NSString *out = [[NSString alloc] initWithData:[outPipe.fileHandleForReading readDataToEndOfFile] encoding:NSUTF8StringEncoding] ?: @"";
    NSString *err = [[NSString alloc] initWithData:[errPipe.fileHandleForReading readDataToEndOfFile] encoding:NSUTF8StringEncoding] ?: @"";
    return @{ @"code": @(task.terminationStatus), @"out": out, @"err": err };
}

- (NSString *)capture:(NSString *)pattern text:(NSString *)text {
    NSRegularExpression *regex = [NSRegularExpression regularExpressionWithPattern:pattern options:0 error:nil];
    NSTextCheckingResult *match = [regex firstMatchInString:text options:0 range:NSMakeRange(0, text.length)];
    if (!match || match.numberOfRanges < 2) return nil;
    return [[text substringWithRange:[match rangeAtIndex:1]] stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
}

- (void)showSettings:(id)sender {
    NSAlert *alert = [[NSAlert alloc] init];
    alert.messageText = @"连接设置";
    alert.informativeText = @"这里只保存健康检查地址和刷新频率，不读取任何密钥。";
    [alert addButtonWithTitle:@"保存"];
    [alert addButtonWithTitle:@"取消"];

    NSView *accessory = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, 430, 82)];
    NSTextField *urlLabel = [NSTextField labelWithString:@"健康检查地址"];
    urlLabel.frame = NSMakeRect(0, 55, 110, 20);
    NSTextField *urlField = [[NSTextField alloc] initWithFrame:NSMakeRect(115, 51, 315, 25)];
    urlField.stringValue = [NSUserDefaults.standardUserDefaults stringForKey:kHealthURLKey];
    NSTextField *intervalLabel = [NSTextField labelWithString:@"自动刷新"];
    intervalLabel.frame = NSMakeRect(0, 16, 110, 20);
    NSPopUpButton *interval = [[NSPopUpButton alloc] initWithFrame:NSMakeRect(115, 12, 140, 26)];
    [interval addItemsWithTitles:@[@"3 秒", @"5 秒", @"10 秒", @"30 秒"]];
    NSArray *values = @[@3, @5, @10, @30];
    NSInteger current = [NSUserDefaults.standardUserDefaults integerForKey:kRefreshIntervalKey];
    NSUInteger selected = [values indexOfObject:@(current)];
    [interval selectItemAtIndex:selected == NSNotFound ? 1 : selected];
    [accessory addSubview:urlLabel]; [accessory addSubview:urlField]; [accessory addSubview:intervalLabel]; [accessory addSubview:interval];
    alert.accessoryView = accessory;
    [alert beginSheetModalForWindow:self.window completionHandler:^(NSModalResponse response) {
        if (response != NSAlertFirstButtonReturn) return;
        [NSUserDefaults.standardUserDefaults setObject:urlField.stringValue forKey:kHealthURLKey];
        [NSUserDefaults.standardUserDefaults setObject:values[interval.indexOfSelectedItem] forKey:kRefreshIntervalKey];
        [self scheduleTimer];
        [self refresh:nil];
    }];
}

- (void)showWindow:(id)sender { [self.window makeKeyAndOrderFront:nil]; [NSApp activateIgnoringOtherApps:YES]; }
- (void)startAll:(id)sender { [self performAll:@"start"]; }
- (void)restartAll:(id)sender { [self performAll:@"restart"]; }
- (void)stopAll:(id)sender { [self performAll:@"stop"]; }
- (void)terminate:(id)sender { [NSApp terminate:nil]; }
- (void)openConfiguration:(id)sender {
    [NSWorkspace.sharedWorkspace openURL:[NSURL fileURLWithPath:[NSHomeDirectory() stringByAppendingPathComponent:@"Library/Application Support/CodexMobileBridge"]]];
}
- (void)openLogs:(id)sender {
    [NSWorkspace.sharedWorkspace openURL:[NSURL fileURLWithPath:[NSHomeDirectory() stringByAppendingPathComponent:@"Library/Logs/CodexMobileBridge"]]];
}
@end

int main(int argc, const char * argv[]) {
    @autoreleasepool {
        NSApplication *app = NSApplication.sharedApplication;
        AppDelegate *delegate = [[AppDelegate alloc] init];
        app.delegate = delegate;
        [app setActivationPolicy:NSApplicationActivationPolicyRegular];
        [app run];
    }
    return 0;
}
