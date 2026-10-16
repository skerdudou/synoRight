// Menu « Droits Synology Photos » du menu contextuel de Windows 11 (IExplorerCommand).
// Aucune logique métier : les entrées sont lues dans HKCU\Software\synoRight\Menu
// (écrit par synoRight) à chaque ouverture du menu, et lancent synoRightGui.exe.
// Aucune exception ne doit traverser une méthode COM : chaque point d'entrée qui alloue
// (std::wstring, std::vector) est protégé par un try/catch.

#include <windows.h>
#include <shlobj_core.h>
#include <shlguid.h>
#include <shobjidl_core.h>
#include <shlwapi.h>
#include <wrl/client.h>
#include <wrl/implements.h>
#include <wrl/module.h>

#include <algorithm>
#include <new>
#include <string>
#include <utility>
#include <vector>

using Microsoft::WRL::ClassicCom;
using Microsoft::WRL::ComPtr;
using Microsoft::WRL::InProc;
using Microsoft::WRL::Make;
using Microsoft::WRL::Module;
using Microsoft::WRL::RuntimeClass;
using Microsoft::WRL::RuntimeClassFlags;

namespace {

constexpr wchar_t MENU_KEY[] = L"Software\\synoRight\\Menu";
constexpr wchar_t ENTRIES_KEY[] = L"Software\\synoRight\\Menu\\Entries";
constexpr wchar_t DEFAULT_ROOT_TITLE[] = L"Synology Photos";

struct Entry {
	std::wstring title;
	std::wstring args;
	bool separatorBefore = false;
};

struct Config {
	std::wstring launcher;
	std::wstring photoFolder;
	std::vector<Entry> entries;
};

// valeur REG_SZ (RRF_RT_REG_SZ accepte aussi REG_EXPAND_SZ, développée par RegGetValueW)
bool ReadString(const HKEY root, const wchar_t* const subKey, const wchar_t* const name, std::wstring& out) {
	DWORD size = 0;
	if (RegGetValueW(root, subKey, name, RRF_RT_REG_SZ, nullptr, nullptr, &size) != ERROR_SUCCESS || size == 0) {
		return false;
	}
	std::wstring buffer(size / sizeof(wchar_t), L'\0');
	if (RegGetValueW(root, subKey, name, RRF_RT_REG_SZ, nullptr, buffer.data(), &size) != ERROR_SUCCESS) {
		return false;
	}
	buffer.resize(wcsnlen(buffer.c_str(), buffer.size()));
	out = std::move(buffer);
	return !out.empty();
}

DWORD ReadDword(const HKEY root, const wchar_t* const subKey, const wchar_t* const name) {
	DWORD value = 0;
	DWORD size = sizeof(value);
	if (RegGetValueW(root, subKey, name, RRF_RT_REG_DWORD, nullptr, &value, &size) != ERROR_SUCCESS) {
		return 0;
	}
	return value;
}

// fermeture de la clé même en cas d'exception
class RegKey {
public:
	RegKey() = default;
	RegKey(const RegKey&) = delete;
	RegKey& operator=(const RegKey&) = delete;
	~RegKey() {
		if (key_ != nullptr) {
			RegCloseKey(key_);
		}
	}
	HKEY* put() { return &key_; }
	HKEY get() const { return key_; }

private:
	HKEY key_ = nullptr;
};

// peut lever std::bad_alloc : à appeler sous try/catch
bool LoadConfig(Config& config) {
	if (!ReadString(HKEY_CURRENT_USER, MENU_KEY, L"Launcher", config.launcher)
		|| !ReadString(HKEY_CURRENT_USER, MENU_KEY, L"PhotoFolder", config.photoFolder)) {
		return false;
	}
	RegKey entries;
	if (RegOpenKeyExW(HKEY_CURRENT_USER, ENTRIES_KEY, 0, KEY_READ, entries.put()) != ERROR_SUCCESS) {
		return false;
	}
	std::vector<std::wstring> names;
	for (DWORD index = 0;; ++index) {
		wchar_t name[256];
		DWORD length = ARRAYSIZE(name);
		const LSTATUS status = RegEnumKeyExW(entries.get(), index, name, &length, nullptr, nullptr, nullptr, nullptr);
		if (status == ERROR_MORE_DATA) {
			continue; // nom trop long : ce n'est pas une entrée 01, 02...
		}
		if (status != ERROR_SUCCESS) {
			break;
		}
		names.emplace_back(name, length);
	}
	std::sort(names.begin(), names.end()); // 01, 02... : ordre d'affichage
	for (const auto& name : names) {
		Entry entry;
		if (ReadString(entries.get(), name.c_str(), L"Title", entry.title) && ReadString(entries.get(), name.c_str(), L"Args", entry.args)) {
			entry.separatorBefore = ReadDword(entries.get(), name.c_str(), L"SeparatorBefore") != 0;
			config.entries.push_back(std::move(entry));
		}
	}
	return !config.entries.empty();
}

std::wstring TrimSlashes(std::wstring path) {
	while (path.size() > 3 && (path.back() == L'\\' || path.back() == L'/')) {
		path.pop_back();
	}
	return path;
}

// dossier égal au dossier photos ou en dessous, sans tenir compte de la casse
bool IsUnder(const std::wstring& path, const std::wstring& root) {
	const std::wstring p = TrimSlashes(path);
	const std::wstring r = TrimSlashes(root);
	if (r.empty() || p.size() < r.size()
		|| CompareStringOrdinal(p.c_str(), static_cast<int>(r.size()), r.c_str(), static_cast<int>(r.size()), TRUE) != CSTR_EQUAL) {
		return false;
	}
	return p.size() == r.size() || r.back() == L'\\' || p[r.size()] == L'\\';
}

bool ItemPath(IShellItem* const item, std::wstring& path) {
	PWSTR name = nullptr;
	if (item == nullptr || FAILED(item->GetDisplayName(SIGDN_FILESYSPATH, &name))) {
		return false;
	}
	try {
		path = name;
	} catch (...) {
		CoTaskMemFree(name);
		throw;
	}
	CoTaskMemFree(name);
	return !path.empty();
}

// dossier affiché par la fenêtre de l'Explorateur (clic droit sur le fond d'un dossier,
// pour lequel la sélection peut être vide)
bool SiteFolderPath(IUnknown* const site, std::wstring& path) {
	ComPtr<IServiceProvider> provider;
	if (site == nullptr || FAILED(site->QueryInterface(IID_PPV_ARGS(&provider)))) {
		return false;
	}
	ComPtr<IShellBrowser> browser;
	ComPtr<IShellView> view;
	ComPtr<IFolderView> folderView;
	ComPtr<IShellItem> folder;
	if (FAILED(provider->QueryService(SID_STopLevelBrowser, IID_PPV_ARGS(&browser)))
		|| FAILED(browser->QueryActiveShellView(&view))
		|| FAILED(view.As(&folderView))
		|| FAILED(folderView->GetFolder(IID_PPV_ARGS(&folder)))) {
		return false;
	}
	return ItemPath(folder.Get(), path);
}

// premier élément sélectionné, sinon dossier courant de la fenêtre
bool TargetPath(IShellItemArray* const items, IUnknown* const site, std::wstring& path) {
	DWORD count = 0;
	if (items != nullptr && SUCCEEDED(items->GetCount(&count)) && count > 0) {
		ComPtr<IShellItem> item;
		return SUCCEEDED(items->GetItemAt(0, &item)) && ItemPath(item.Get(), path);
	}
	return SiteFolderPath(site, path);
}

// remplace %V par le dossier ; "P:\" entre guillemets : le \ final échapperait le guillemet
std::wstring ExpandArgs(const std::wstring& args, std::wstring path) {
	if (!path.empty() && path.back() == L'\\') {
		path.push_back(L'\\');
	}
	std::wstring result = args;
	for (size_t pos = 0; (pos = result.find(L"%V", pos)) != std::wstring::npos; pos += path.size()) {
		result.replace(pos, 2, path);
	}
	return result;
}

class SubCommand final : public RuntimeClass<RuntimeClassFlags<ClassicCom>, IExplorerCommand, IObjectWithSite> {
public:
	SubCommand(std::wstring title, std::wstring launcher, std::wstring args, const bool separator, IUnknown* const site)
		: title_(std::move(title)), launcher_(std::move(launcher)), args_(std::move(args)), separator_(separator), site_(site) {}

	IFACEMETHODIMP GetTitle(IShellItemArray*, LPWSTR* const name) override {
		*name = nullptr;
		return separator_ ? E_NOTIMPL : SHStrDupW(title_.c_str(), name);
	}
	IFACEMETHODIMP GetIcon(IShellItemArray*, LPWSTR* const icon) override {
		*icon = nullptr;
		return E_NOTIMPL;
	}
	IFACEMETHODIMP GetToolTip(IShellItemArray*, LPWSTR* const tip) override {
		*tip = nullptr;
		return E_NOTIMPL;
	}
	IFACEMETHODIMP GetCanonicalName(GUID* const guid) override {
		*guid = GUID_NULL;
		return S_OK;
	}
	IFACEMETHODIMP GetState(IShellItemArray*, BOOL, EXPCMDSTATE* const state) override {
		*state = ECS_ENABLED;
		return S_OK;
	}
	IFACEMETHODIMP Invoke(IShellItemArray* const items, IBindCtx*) override {
		if (separator_) {
			return S_OK;
		}
		try {
			std::wstring path;
			if (!TargetPath(items, site_.Get(), path)) {
				return S_OK;
			}
			std::wstring commandLine = L"\"" + launcher_ + L"\" " + ExpandArgs(args_, path);
			STARTUPINFOW startup{};
			startup.cb = sizeof(startup);
			PROCESS_INFORMATION process{};
			if (!CreateProcessW(launcher_.c_str(), commandLine.data(), nullptr, nullptr, FALSE, 0, nullptr, nullptr, &startup, &process)) {
				return HRESULT_FROM_WIN32(GetLastError());
			}
			CloseHandle(process.hThread);
			CloseHandle(process.hProcess);
			return S_OK;
		} catch (const std::bad_alloc&) {
			return E_OUTOFMEMORY;
		} catch (...) {
			return E_FAIL;
		}
	}
	IFACEMETHODIMP GetFlags(EXPCMDFLAGS* const flags) override {
		*flags = separator_ ? ECF_ISSEPARATOR : ECF_DEFAULT;
		return S_OK;
	}
	IFACEMETHODIMP EnumSubCommands(IEnumExplorerCommand** const commands) override {
		*commands = nullptr;
		return E_NOTIMPL;
	}

	IFACEMETHODIMP SetSite(IUnknown* const site) override {
		site_ = site;
		return S_OK;
	}
	IFACEMETHODIMP GetSite(REFIID riid, void** const site) override {
		*site = nullptr;
		return site_ ? site_.CopyTo(riid, site) : E_FAIL;
	}

private:
	std::wstring title_;
	std::wstring launcher_;
	std::wstring args_;
	bool separator_;
	ComPtr<IUnknown> site_;
};

class CommandEnum final : public RuntimeClass<RuntimeClassFlags<ClassicCom>, IEnumExplorerCommand> {
public:
	explicit CommandEnum(std::vector<ComPtr<IExplorerCommand>> commands) : commands_(std::move(commands)) {}

	IFACEMETHODIMP Next(const ULONG count, IExplorerCommand** const out, ULONG* const fetched) override {
		if (out == nullptr || (fetched == nullptr && count > 1)) {
			return E_INVALIDARG;
		}
		ULONG done = 0;
		for (; done < count && index_ < commands_.size(); ++done, ++index_) {
			commands_[index_].CopyTo(&out[done]);
		}
		if (fetched != nullptr) {
			*fetched = done;
		}
		return done == count ? S_OK : S_FALSE;
	}
	IFACEMETHODIMP Skip(const ULONG count) override {
		index_ = (std::min)(index_ + count, commands_.size());
		return index_ < commands_.size() ? S_OK : S_FALSE;
	}
	IFACEMETHODIMP Reset() override {
		index_ = 0;
		return S_OK;
	}
	IFACEMETHODIMP Clone(IEnumExplorerCommand** const out) override {
		*out = nullptr;
		return E_NOTIMPL;
	}

private:
	std::vector<ComPtr<IExplorerCommand>> commands_;
	size_t index_ = 0;
};

} // namespace

// Hors du namespace anonyme : CoCreatableClass référence l'entrée de fabrique par un nom
// décoré (/include du linker), qui doit être visible hors de cette unité de compilation.
class __declspec(uuid("7B3F9E62-5C1A-4D8B-9E2F-3A6C1D4B8F10")) RootCommand final
	: public RuntimeClass<RuntimeClassFlags<ClassicCom>, IExplorerCommand, IObjectWithSite, IObjectWithSelection> {
public:
	IFACEMETHODIMP GetTitle(IShellItemArray*, LPWSTR* const name) override {
		// titre écrit par synoRight dans la langue choisie ; absent (ancienne installation) : repli
		try {
			std::wstring title;
			if (ReadString(HKEY_CURRENT_USER, MENU_KEY, L"RootTitle", title)) {
				return SHStrDupW(title.c_str(), name);
			}
		} catch (...) {
			// repli ci-dessous
		}
		return SHStrDupW(DEFAULT_ROOT_TITLE, name);
	}
	IFACEMETHODIMP GetIcon(IShellItemArray*, LPWSTR* const icon) override {
		*icon = nullptr;
		try {
			std::wstring launcher;
			if (!ReadString(HKEY_CURRENT_USER, MENU_KEY, L"Launcher", launcher)) {
				return E_FAIL;
			}
			return SHStrDupW((launcher + L",0").c_str(), icon);
		} catch (const std::bad_alloc&) {
			return E_OUTOFMEMORY;
		} catch (...) {
			return E_FAIL;
		}
	}
	IFACEMETHODIMP GetToolTip(IShellItemArray*, LPWSTR* const tip) override {
		*tip = nullptr;
		return E_NOTIMPL;
	}
	IFACEMETHODIMP GetCanonicalName(GUID* const guid) override {
		*guid = __uuidof(RootCommand);
		return S_OK;
	}
	IFACEMETHODIMP GetState(IShellItemArray* const items, BOOL, EXPCMDSTATE* const state) override {
		// toute erreur : entrée masquée (jamais d'exception vers l'Explorateur)
		*state = ECS_HIDDEN;
		stateQueried_ = true;
		try {
			// menu « Afficher plus d'options » alors que le menu classique (registre) y est aussi installé :
			// on s'efface pour ne pas apparaître deux fois
			if (legacyMenu_ && ReadDword(HKEY_CURRENT_USER, MENU_KEY, L"HideInLegacyMenu") != 0) {
				return S_OK;
			}
			Config config;
			std::wstring path;
			if (LoadConfig(config) && TargetPath(items != nullptr ? items : selection_.Get(), site_.Get(), path)
				&& IsUnder(path, config.photoFolder)) {
				*state = ECS_ENABLED;
			}
		} catch (...) {
			*state = ECS_HIDDEN;
		}
		return S_OK;
	}
	IFACEMETHODIMP Invoke(IShellItemArray*, IBindCtx*) override {
		return E_NOTIMPL;
	}
	IFACEMETHODIMP GetFlags(EXPCMDFLAGS* const flags) override {
		*flags = ECF_HASSUBCOMMANDS;
		return S_OK;
	}
	IFACEMETHODIMP EnumSubCommands(IEnumExplorerCommand** const commands) override {
		*commands = nullptr;
		try {
			Config config;
			if (!LoadConfig(config)) {
				return E_FAIL;
			}
			std::vector<ComPtr<IExplorerCommand>> list;
			for (const auto& entry : config.entries) {
				if (entry.separatorBefore && !list.empty()) {
					list.push_back(Make<SubCommand>(L"", L"", L"", true, nullptr));
				}
				list.push_back(Make<SubCommand>(entry.title, config.launcher, entry.args, false, site_.Get()));
			}
			for (const auto& command : list) {
				if (!command) {
					return E_OUTOFMEMORY; // Make renvoie nullptr si l'allocation échoue
				}
			}
			const ComPtr<CommandEnum> enumerator = Make<CommandEnum>(std::move(list));
			return enumerator ? enumerator.CopyTo(commands) : E_OUTOFMEMORY;
		} catch (const std::bad_alloc&) {
			return E_OUTOFMEMORY;
		} catch (...) {
			return E_FAIL;
		}
	}

	IFACEMETHODIMP SetSite(IUnknown* const site) override {
		site_ = site;
		return S_OK;
	}
	IFACEMETHODIMP GetSite(REFIID riid, void** const site) override {
		*site = nullptr;
		return site_ ? site_.CopyTo(riid, site) : E_FAIL;
	}

	// Pas d'API pour savoir dans quel menu on est : le menu « Afficher plus d'options » appelle SetSelection
	// avant tout GetState, le menu principal de Windows 11 commence par GetState (ordre constaté, non documenté).
	// Si l'ordre change, on retombe sur l'affichage dans les deux menus, jamais sur une entrée disparue du menu principal.
	IFACEMETHODIMP SetSelection(IShellItemArray* const items) override {
		if (!stateQueried_) {
			legacyMenu_ = true;
		}
		selection_ = items;
		return S_OK;
	}
	IFACEMETHODIMP GetSelection(REFIID riid, void** const items) override {
		*items = nullptr;
		return selection_ ? selection_.CopyTo(riid, items) : E_FAIL;
	}

private:
	ComPtr<IUnknown> site_;
	ComPtr<IShellItemArray> selection_;
	bool stateQueried_ = false;
	bool legacyMenu_ = false;
};

CoCreatableClass(RootCommand)

STDAPI DllCanUnloadNow() {
	return Module<InProc>::GetModule().GetObjectCount() == 0 ? S_OK : S_FALSE;
}

STDAPI DllGetClassObject(REFCLSID clsid, REFIID riid, LPVOID* object) {
	return Module<InProc>::GetModule().GetClassObject(clsid, riid, object);
}

BOOL APIENTRY DllMain(const HMODULE module, const DWORD reason, LPVOID) {
	if (reason == DLL_PROCESS_ATTACH) {
		DisableThreadLibraryCalls(module);
	}
	return TRUE;
}
