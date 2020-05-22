/*
 * Copyright (c) 2020-2020 the original author or authors.
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

require({
        baseUrl: 'js/jquery',
        paths: {
            jquery: "https://code.jquery.com/jquery-3.4.1",
            cometd: '../cometd'
        }
    },
    ['jquery', 'jquery.cometd'],
    function($, cometd) {
        $(document).ready(function() {
            const path = location.pathname;
            const contextPath = path.substring(0, path.lastIndexOf('/'));

            // Setup UI
            // TODO

            // Initialize CometD
            const cometURL = location.protocol + '//' + location.host + contextPath + '/cometd';
            cometd.configure({
                url: cometURL,
                logLevel: 'info'
            });
            cometd.addListener('/meta/handshake', message => {
                if (message.successful) {
                    cometd._info('Handshake Successful');
                    cometd.batch(() => {
                        // TODO
                    });
                }
            });

            cometd.handshake();
        });
    }
);
