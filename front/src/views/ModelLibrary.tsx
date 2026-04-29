import { useState } from 'react';
import { X, Droplet as Water, Plane, Car, Earth, History, CheckCircle2, RotateCcw, Save } from 'lucide-react';
import { motion, AnimatePresence } from 'motion/react';

const models = [
  {
    id: 'AeroScan_v4',
    name: 'AeroScan_v4',
    type: 'Fixed-wing & Rotary Detection',
    category: 'Goal Detection',
    status: 'Active',
    precision: '0.982',
    recall: '0.965',
    image: 'https://lh3.googleusercontent.com/aida-public/AB6AXuDmFttOx_faxntf0k1l_rqhjbFgMaVZooulV9SDJ3E3Hd9IyQQPQWYnFMNIf3UdSboW84FWHED0NBB-FchTec29avg6-jC3ifaPM0qAgUA_DNwGIfOKxl3DONvsuJPFfVKcMX-4dJBpq5ejMuCNmTpCez_I6Mgz394fcVSgGJBYP8Ih-bXKdbUb0hIVt7UQCkQ5HByycTyL6VHwj5gS5j1RAhLeuIuC8roOfA2iSqDBUcvNZj4pQe_xSBIfM7zWytelcB3fx_dRX-4',
    icon: Plane
  },
  {
    id: 'UrbanFlow_MX',
    name: 'UrbanFlow_MX',
    type: 'Ground Vehicle Telemetry',
    category: 'Goal Detection',
    status: 'Idle',
    precision: '0.924',
    recall: '0.899',
    image: 'https://lh3.googleusercontent.com/aida-public/AB6AXuA38H8EkMSS3qN5hHmgq2WhM64E1KLv4OpSW47qkX-CurkYIKbKvIXUko1d9BEjJz8ikZabnbysvDVqm_a4tJLU0VrrzYuaMIPFZeHmqKUsDQJEAbRBbSWsOVay1XhwWoI_HKyRzDyAZK44j-X3xgoEbWJ8hcpJLgYQvaEfEELh5AZG8DcJy2mJSjhmC0Qv4P9xrIG_lQKgL-BHnk4ZeMQ-jilK1qvCII2wsSlXCASZghRHn3i-nzNiWvupjjdgimERxN-XFDo64e0',
    icon: Car
  },
  {
    id: 'TerraClassify',
    name: 'TerraClassify',
    type: 'Multi-class Land Use',
    category: 'Semantic Segmentation',
    status: 'Active',
    precision: '0.842',
    recall: '0.971',
    image: 'https://lh3.googleusercontent.com/aida-public/AB6AXuBYvpqLs_OZUzzVXJ2ap4VzmqIyeX23ryuv41Q0HNs16l5eialJswXecX_FImzM_d8zApPTRNQVcRabr6e5w92HK0Fm9OnMGnceR0lwJIFfXjZyouyxE5415k27_NfXJjD-v2L1J4Zl1NX7JvCTN161Ps-5r76okQX1YHJ4dPFi7mZNxoSLSCR-7mfcBRS2qkgnnmhP7dT-rN3NbXEGUND0Xy86tjb-GOAZM-ZQJna6-4Beu_jqisBktG2EAxf4QXiZUMoTbe_0fK4',
    icon: Earth
  },
  {
    id: 'HydraExtract_Pro',
    name: 'HydraExtract_Pro',
    type: 'Water Body Delineation',
    category: 'Semantic Segmentation',
    status: 'Active',
    precision: '0.991',
    recall: '0.988',
    image: 'https://lh3.googleusercontent.com/aida-public/AB6AXuB18xC7k9y3rO0BXeCLagXWb7hrecDXqhO_Yx2yW2JL4dqUQqjuf3z1uCnrtJCbzColncOOJYO79_bn9WL-GdnBK1FpDXw7ucYkWwrIpcsfpNTPfxoaQv25VP9w_MoFUWqAwljpPqogOH1A_vbcVKWPEeM2x5Z1DRYPa6yWPoxXoF-frYNbJ6mKBNqsZDhEYYXH_PvZVi9AAGG-oubz-aiT0pH0h8FhTbnommS3GfMuGzeAQWlHtd7PCpcxKWF8v3gmDbXsxiWnO38',
    icon: Water
  },
  {
    id: 'TemporalShift',
    name: 'TemporalShift',
    type: 'Urban Structural Change',
    category: 'Change Detection',
    status: 'Offline',
    precision: '0.875',
    recall: '0.862',
    image: 'https://lh3.googleusercontent.com/aida-public/AB6AXuCt85ut3Lz2csInzjaCnaavzHv8BNaxh620UhKbk2eXwtWXjrswYExrBDYRhtpc_w7ZpxzyB8OC0zol53_0uG2M0KNp3YR9lDoCZZK3ZINn8WFsn1i67NRPSzf-sSpo7waQPEfccAR0Duv-TA9ccT1dr74VZmLvnjVnvb-2H0th7CPbaIN9Zr-EmrB1mDnO8_7Q7P8Np1gHpoxh1Kqzr82SEqVNq6Vc_knjuBVUj9vrGxkrawTKXB9eYGu3usocCmimNUNCEngC7HI',
    icon: History
  }
];

export default function ModelLibrary() {
  const [selectedModel, setSelectedModel] = useState(models[3]); // HydraExtract_Pro as default
  const [isConfigOpen, setIsConfigOpen] = useState(true);

  const categories = Array.from(new Set(models.map(m => m.category)));

  return (
    <div className="h-full flex bg-[#f8f9fa] overflow-hidden relative">
      {/* Scrollable Model Grid */}
      <section className="flex-1 overflow-y-auto p-8 custom-scrollbar">
        <div className="max-w-6xl mx-auto space-y-10 pb-20">
          {/* Page Header */}
          <header className="flex justify-between items-end pb-4 border-b border-slate-200">
            <div>
              <h1 className="font-headline text-3xl font-bold tracking-tight text-slate-800">Model Library</h1>
              <p className="font-sans text-slate-500 text-sm mt-1 opacity-80">Configure geospatial AI algorithms and operators.</p>
            </div>
            <div className="flex gap-2">
              <div className="bg-white px-4 py-2 rounded-lg flex items-center gap-3 border border-slate-200 shadow-sm">
                <span className="text-[10px] font-sans uppercase tracking-widest text-slate-400 font-bold">Compute Status</span>
                <span className="flex items-center gap-1.5 text-emerald-600 text-xs font-bold font-headline">
                  <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse transition-all"></span>
                  OPTIMAL
                </span>
              </div>
            </div>
          </header>

          {categories.map((cat) => (
            <div key={cat} className="space-y-6">
              <div className="flex items-center gap-4">
                <h2 className="font-headline text-base font-bold text-slate-400 uppercase tracking-[0.2em]">{cat}</h2>
                <div className="h-[1px] flex-1 bg-slate-200"></div>
              </div>
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                {models.filter(m => m.category === cat).map((model) => (
                  <motion.div 
                    key={model.id}
                    layoutId={`model-card-${model.id}`}
                    onClick={() => { setSelectedModel(model); setIsConfigOpen(true); }}
                    className={`group relative rounded-xl overflow-hidden p-0 flex transition-all cursor-pointer border shadow-sm
                      ${selectedModel.id === model.id ? 'border-indigo-600 ring-2 ring-indigo-100 bg-white' : 'border-slate-200 bg-white hover:border-indigo-300'}`}
                  >
                    <div className="w-28 h-full relative overflow-hidden bg-slate-100 border-r border-slate-100">
                      <img 
                        className={`object-cover w-full h-full transition-opacity duration-500
                          ${selectedModel.id === model.id ? 'opacity-100' : 'opacity-60 group-hover:opacity-90'}`} 
                        src={model.image} 
                        referrerPolicy="no-referrer"
                      />
                    </div>
                    <div className="flex-1 p-4 relative">
                      <div className="flex justify-between items-start mb-2">
                        <div>
                          <h3 className={`font-headline text-lg font-bold ${selectedModel.id === model.id ? 'text-indigo-600' : 'text-slate-800'}`}>{model.name}</h3>
                          <p className="text-[10px] font-sans text-slate-400 uppercase tracking-widest font-bold">{model.type}</p>
                        </div>
                        <span className={`px-2 py-0.5 rounded text-[9px] font-bold uppercase border
                          ${model.status === 'Active' ? 'bg-emerald-50 text-emerald-600 border-emerald-100' : 
                            model.status === 'Idle' ? 'bg-slate-50 text-slate-400 border-slate-200' : 
                            'bg-rose-50 text-rose-600 border-rose-100'}`}>
                          {model.status}
                        </span>
                      </div>
                      <div className="grid grid-cols-2 gap-3 mt-4">
                        <div className="bg-slate-50 p-2 rounded border border-slate-100">
                          <div className="text-[9px] text-slate-400 uppercase mb-0.5 font-bold tracking-tight opacity-80">
                            {cat === 'Semantic Segmentation' ? 'mIOU' : 'Precision'}
                          </div>
                          <div className={`font-headline text-lg font-bold ${selectedModel.id === model.id ? 'text-emerald-600' : 'text-slate-700'}`}>{model.precision}</div>
                        </div>
                        <div className="bg-slate-50 p-2 rounded border border-slate-100">
                          <div className="text-[9px] text-slate-400 uppercase mb-0.5 font-bold tracking-tight opacity-80">
                            {cat === 'Semantic Segmentation' ? 'Accuracy' : 'Recall'}
                          </div>
                          <div className={`font-headline text-lg font-bold ${selectedModel.id === model.id ? 'text-emerald-600' : 'text-slate-700'}`}>{model.recall}</div>
                        </div>
                      </div>

                      {selectedModel.id === model.id && (
                        <div className="absolute -top-1 -right-1 bg-indigo-600 rounded-full p-1 shadow-lg text-white">
                          <CheckCircle2 className="w-3.5 h-3.5" />
                        </div>
                      )}
                    </div>
                  </motion.div>
                ))}
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* Right Configuration Side Panel */}
      <AnimatePresence>
        {isConfigOpen && (
          <motion.aside 
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ type: 'spring', damping: 25, stiffness: 200 }}
            className="w-80 bg-white border-l border-slate-200 flex flex-col z-20 shadow-[-10px_0_30px_rgba(0,0,0,0.05)]"
          >
            <div className="p-6 border-b border-slate-100">
              <div className="flex items-center justify-between mb-6">
                <span className="text-[10px] font-sans font-bold text-indigo-600 tracking-widest uppercase bg-indigo-50 px-2 py-1 rounded inline-block">Configuration</span>
                <button onClick={() => setIsConfigOpen(false)} className="text-slate-400 hover:text-slate-800 transition-colors p-1.5 rounded-full hover:bg-slate-100">
                  <X className="w-4 h-4" />
                </button>
              </div>
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded bg-slate-50 flex items-center justify-center border border-slate-200 shadow-sm text-indigo-600">
                  <selectedModel.icon className="w-6 h-6" />
                </div>
                <div>
                  <h2 className="font-headline text-lg font-bold text-slate-800">{selectedModel.name}</h2>
                  <p className="text-[10px] text-slate-500 font-bold uppercase tracking-widest">v3.2.1 Stable</p>
                </div>
              </div>
            </div>

            <div className="flex-1 overflow-y-auto p-6 space-y-8 custom-scrollbar">
              <div className="space-y-6">
                <h3 className="text-[10px] font-sans font-bold text-slate-400 uppercase tracking-widest flex items-center gap-2">
                  <span className="w-1 h-1 rounded-full bg-indigo-500" />
                  Hyperparameters
                </h3>
                
                <div className="space-y-3">
                  <div className="flex justify-between items-center">
                    <label className="text-[11px] font-bold text-slate-600 uppercase tracking-tight">Confidence Threshold</label>
                    <span className="font-headline text-indigo-600 font-bold text-sm">0.75</span>
                  </div>
                  <div className="relative w-full h-1 bg-slate-100 rounded-full cursor-pointer group">
                    <div className="absolute top-0 left-0 h-full bg-indigo-500 rounded-full w-[75%]"></div>
                    <div className="absolute top-1/2 left-[75%] -translate-y-1/2 w-3.5 h-3.5 bg-white rounded-full shadow-md border border-indigo-500"></div>
                  </div>
                </div>

                <div className="space-y-3">
                  <div className="flex justify-between items-center">
                    <label className="text-[11px] font-bold text-slate-600 uppercase tracking-tight">NMS Threshold</label>
                    <span className="font-headline text-indigo-600 font-bold text-sm">0.45</span>
                  </div>
                  <div className="relative w-full h-1 bg-slate-100 rounded-full cursor-pointer group">
                    <div className="absolute top-0 left-0 h-full bg-indigo-500 rounded-full w-[45%]"></div>
                    <div className="absolute top-1/2 left-[45%] -translate-y-1/2 w-3.5 h-3.5 bg-white rounded-full shadow-md border border-indigo-500"></div>
                  </div>
                </div>

                <div className="flex items-center justify-between p-3 bg-slate-50 rounded-lg border border-slate-100 hover:border-indigo-200 transition-all cursor-pointer group">
                  <div className="text-[11px] font-bold text-slate-700">Multi-Scale Inference</div>
                  <div className="w-10 h-5 bg-indigo-100 rounded-full relative flex items-center px-1 border border-indigo-200">
                    <div className="w-3 h-3 bg-indigo-600 rounded-full ml-auto shadow-sm" />
                  </div>
                </div>
              </div>

              <div className="space-y-4 pt-4">
                <h3 className="text-[10px] font-sans font-bold text-slate-400 uppercase tracking-widest flex items-center gap-2">
                  <span className="w-1 h-1 rounded-full bg-emerald-500" />
                  Resources
                </h3>
                <div className="grid grid-cols-2 gap-3">
                  <div className="bg-slate-50 p-3 rounded-lg border border-slate-100">
                    <div className="text-[9px] text-slate-400 uppercase mb-0.5 font-bold tracking-tight">VRAM</div>
                    <div className="font-headline text-base font-bold text-slate-800">4.2 GB</div>
                  </div>
                  <div className="bg-slate-50 p-3 rounded-lg border border-slate-100">
                    <div className="text-[9px] text-slate-400 uppercase mb-0.5 font-bold tracking-tight">Batch</div>
                    <div className="font-headline text-base font-bold text-slate-800">16</div>
                  </div>
                </div>
              </div>
            </div>

            <div className="p-6 border-t border-slate-100 bg-slate-50/50">
              <div className="flex gap-2">
                <button className="flex-1 py-2 text-slate-500 text-[10px] font-bold uppercase tracking-widest hover:bg-slate-100 rounded border border-slate-200 transition-all">
                  Reset
                </button>
                <button className="flex-1 py-2 bg-indigo-600 text-white text-[10px] font-bold uppercase tracking-widest rounded shadow-md hover:bg-indigo-500 transition-all flex items-center justify-center gap-2">
                  <Save className="w-3 h-3" />
                  Save Changes
                </button>
              </div>
            </div>
          </motion.aside>
        )}
      </AnimatePresence>
    </div>
  );
}

