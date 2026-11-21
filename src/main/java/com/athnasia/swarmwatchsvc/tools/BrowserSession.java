package com.athnasia.swarmwatchsvc.tools;

interface BrowserSession extends AutoCloseable {
    void perform(ComputerAction action);
    byte[] screenshot();
    String currentUrl();
    @Override void close();

    interface Factory extends AutoCloseable {
        BrowserSession open();
        @Override void close();
    }
}
