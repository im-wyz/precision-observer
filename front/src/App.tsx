/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import { useState, useCallback } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import Header from './components/Header';
import Sidebar from './components/Sidebar';
import AIWorkspace from './views/AIWorkspace';
import ResourceLibrary from './views/ResourceLibrary';
import ModelLibrary from './views/ModelLibrary';
import BusinessLibrary from './views/BusinessLibrary';

export type ViewType = 'workspace' | 'resources' | 'models' | 'analytics';

export default function App() {
  const [activeView, setActiveView] = useState<ViewType>('workspace');
  const [pendingWorkspaceSessionId, setPendingWorkspaceSessionId] = useState<string | null>(null);
  const [newWorkspaceNonce, setNewWorkspaceNonce] = useState(0);

  const onWorkspaceSessionPendingConsumed = useCallback(() => {
    setPendingWorkspaceSessionId(null);
  }, []);

  const startBlankWorkspace = useCallback(() => {
    setPendingWorkspaceSessionId(null);
    setActiveView('workspace');
    setNewWorkspaceNonce((n) => n + 1);
  }, []);

  const renderView = () => {
    switch (activeView) {
      case 'workspace':
        return (
          <AIWorkspace
            pendingSessionId={pendingWorkspaceSessionId}
            onPendingSessionConsumed={onWorkspaceSessionPendingConsumed}
            newWorkspaceNonce={newWorkspaceNonce}
          />
        );
      case 'resources':
        return (
          <ResourceLibrary
            onOpenWorkspaceSession={(id) => {
              setPendingWorkspaceSessionId(id);
              setActiveView('workspace');
            }}
            onCreateWorkspace={startBlankWorkspace}
          />
        );
      case 'models':
        return <ModelLibrary key="models" />;
      case 'analytics':
        return <BusinessLibrary key="analytics" />;
      default:
        return (
          <AIWorkspace
            pendingSessionId={pendingWorkspaceSessionId}
            onPendingSessionConsumed={onWorkspaceSessionPendingConsumed}
            newWorkspaceNonce={newWorkspaceNonce}
          />
        );
    }
  };

  return (
    <div className="flex h-screen bg-background text-on-surface overflow-hidden font-sans">
      <Header activeView={activeView} onViewChange={setActiveView} />

      <div className="flex flex-1 pt-12 overflow-hidden">
        <Sidebar activeView={activeView} onViewChange={setActiveView} onStartNewWorkspace={startBlankWorkspace} />

        <main className="flex-1 ml-64 overflow-hidden relative">
          <AnimatePresence mode="wait">
            <motion.div
              key={activeView}
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -10 }}
              transition={{ duration: 0.3, ease: 'easeOut' }}
              className="h-full w-full"
            >
              {renderView()}
            </motion.div>
          </AnimatePresence>
        </main>
      </div>
    </div>
  );
}
